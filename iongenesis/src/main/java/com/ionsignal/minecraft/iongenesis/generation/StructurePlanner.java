package com.ionsignal.minecraft.iongenesis.generation;

import com.ionsignal.minecraft.iongenesis.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.iongenesis.generation.components.JigsawPool;
import com.ionsignal.minecraft.iongenesis.generation.components.JigsawProvider;
import com.ionsignal.minecraft.iongenesis.generation.components.PoolRegistry;
import com.ionsignal.minecraft.iongenesis.generation.enforcement.ConstraintViolation;
import com.ionsignal.minecraft.iongenesis.generation.enforcement.EnforcementStrategy;
import com.ionsignal.minecraft.iongenesis.generation.enforcement.ForcedPlacement;
import com.ionsignal.minecraft.iongenesis.generation.engine.PlanningEventListener;
import com.ionsignal.minecraft.iongenesis.generation.logic.CandidateSelector;
import com.ionsignal.minecraft.iongenesis.generation.logic.ConnectionFitter;
import com.ionsignal.minecraft.iongenesis.generation.logic.JigsawConnection;
import com.ionsignal.minecraft.iongenesis.generation.logic.TerrainTrend;
import com.ionsignal.minecraft.iongenesis.generation.oracle.CachedTerrainOracle;
import com.ionsignal.minecraft.iongenesis.generation.oracle.TerrainOracle;
import com.ionsignal.minecraft.iongenesis.generation.placements.PendingJigsawConnection;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.iongenesis.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.iongenesis.generation.tracking.GenerationStatistics;
import com.ionsignal.minecraft.iongenesis.generation.tracking.PoolUsageTracker;
import com.ionsignal.minecraft.iongenesis.generation.tracking.UsageConstraints;
import com.ionsignal.minecraft.iongenesis.model.geometry.CollisionDetector;
import com.ionsignal.minecraft.iongenesis.model.geometry.NaiveCollisionDetector;
import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure;
import com.ionsignal.minecraft.iongenesis.util.ResourceResolver;
import com.ionsignal.minecraft.iongenesis.util.TransformUtil;

import com.dfsek.seismic.type.DistanceFunction;
import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.structure.Structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * State machine based structure planner. Orchestrates the generation process by delegating specific
 * logic to CandidateSelector and ConnectionFitter.
 */
public class StructurePlanner {
    private static final Logger LOGGER = Logger.getLogger(StructurePlanner.class.getName());
    private static final int MAX_OPS_PER_TICK = 100; // Safety valve to prevent main thread freeze
    private static final int PROBE_PROJECTION_DISTANCE = 3;

    public enum State {
        INITIALIZING, GENERATING, ENFORCING, FINISHED
    }

    // Inputs
    private final ConfigPack pack;
    private final JigsawStructureTemplate config;
    private final Vector3Int origin;
    private final RandomGenerator random;
    private final PlanningEventListener listener;
    private final UUID sessionId;
    private final TerrainOracle oracle;

    // Logic Delegates
    private final CandidateSelector candidateSelector;
    private final ConnectionFitter connectionFitter;
    private final ForcedPlacement forcedPlacement;

    // Internal State
    private State state = State.INITIALIZING;
    private final PoolRegistry poolRegistry;
    private final List<PlacedJigsawPiece> pieces = new ArrayList<>();
    private final PriorityQueue<PendingJigsawConnection> connectionQueue = new PriorityQueue<>();
    private final CollisionDetector collisionDetector = new NaiveCollisionDetector();
    private final PoolUsageTracker usageTracker = new PoolUsageTracker();
    private final ConnectionRegistry connectionRegistry = new ConnectionRegistry();
    private final EnforcementStrategy enforcementStrategy;
    private final List<UsageConstraints> allConstraints;
    private final List<ConstraintViolation> capturedViolations = new ArrayList<>();
    private final Queue<UsageConstraints> pendingEnforcement = new LinkedList<>();

    // Probe
    private StructureBlueprint.ProbeResult lastProbeResult;

    // Metrics
    private int attemptedConnections = 0;
    private int successfulConnections = 0;
    private long generationStartTime;

    public StructurePlanner(
            ConfigPack pack,
            JigsawStructureTemplate config,
            Vector3Int origin,
            RandomGenerator random, long seed,
            PlanningEventListener listener, UUID sessionId,
            TerrainOracle oracle) {
        this.pack = pack;
        this.config = config;
        this.origin = origin;
        this.random = random;
        this.listener = listener;
        this.sessionId = sessionId;
        this.oracle = new CachedTerrainOracle(oracle);
        this.poolRegistry = new PoolRegistry(pack);
        this.enforcementStrategy = EnforcementStrategy.fromConfig(config.getEnforcementStrategy());
        this.allConstraints = gatherConstraintsFromPools();

        // Initialize Logic Delegates
        this.candidateSelector = new CandidateSelector(random, usageTracker);
        this.connectionFitter = new ConnectionFitter(pack, random, collisionDetector, config);
        this.forcedPlacement = new ForcedPlacement(pack, seed);
    }

    public StructureBlueprint generateFull(String startPoolId) {
        initialize(startPoolId);
        while (tick()) {
            // Loop until finished
        }
        return getBlueprint();
    }

    public boolean tick() {
        this.lastProbeResult = null;
        switch (state) {
            case INITIALIZING:
                return false;
            case GENERATING:
                if (connectionQueue.isEmpty()) {
                    LOGGER.info("[Planner] Transitioning to ENFORCING phase");
                    state = State.ENFORCING;
                    prepareEnforcementQueue();
                    return true;
                }
                // Safety Valve Loop
                int ops = 0;
                while (!connectionQueue.isEmpty() && ops < MAX_OPS_PER_TICK) {
                    boolean didWork = processNextConnection();
                    if (didWork) {
                        return true; // Yield to driver for visualization
                    }
                    ops++;
                }
                // Always return true to allow the next tick to handle the empty queue transition
                return true;
            case ENFORCING:
                if (pendingEnforcement.isEmpty()) {
                    checkStrictEnforcementFailure();
                    LOGGER.info("[Planner] Transitioning to FINISHED phase. Returning FALSE to stop driver.");
                    state = State.FINISHED;
                    return false;
                }
                // Same Safety Valve logic for Enforcement
                int enforcementOps = 0;
                while (!pendingEnforcement.isEmpty() && enforcementOps < MAX_OPS_PER_TICK) {
                    boolean didWork = processNextEnforcement();
                    if (didWork) {
                        return true;
                    }
                    enforcementOps++;
                }
                // Always return true to allow the next tick to handle the empty queue transition
                return true;
            case FINISHED:
                LOGGER.info("[Planner] In FINISHED state. Returning FALSE.");
                return false;
            default:
                return false;
        }
    }

    public void initialize(String startPoolId) {
        generationStartTime = System.currentTimeMillis();
        capturedViolations.clear();
        // Root Node Snapping
        Optional<Integer> surfaceYOpt = oracle.getSurfaceHeight(origin.getX(), origin.getZ());
        if (surfaceYOpt.isEmpty()) {
            LOGGER.warning("[Planner] Failed to detect terrain at origin " + origin + ". Aborting generation.");
            state = State.FINISHED;
            return;
        }
        int surfaceY = surfaceYOpt.get();
        int startY = surfaceY + config.getYLevel();
        Vector3Int startPos = Vector3Int.of(origin.getX(), startY, origin.getZ());
        PlacedJigsawPiece startPiece = selectAndPlaceStartPiece(startPoolId, startPos);
        if (startPiece != null) {
            addPiece(startPiece);
            queueConnections(startPiece, 0);
            state = State.GENERATING;
        } else {
            LOGGER.warning("Failed to place start piece.");
            state = State.FINISHED;
        }
    }

    /**
     * Processes the next connection in the queue.
     * 
     * @return true if a piece was placed (visual change), false if skipped/failed.
     */
    private boolean processNextConnection() {
        PendingJigsawConnection pending = connectionQueue.poll();
        if (pending == null)
            return false;
        if (pending.depth() > config.getMaxDepth()) {
            return handlePanicMode(pending);
        }
        if (pending.isTerminator()) {
            connectionRegistry.markConsumed(pending.connection().position());
            return false;
        }
        if (pending.exceedsDistance(origin, config.getMaxDistance())) {
            connectionRegistry.markSealed(pending.connection().position());
            LOGGER.info("Sealed connection due to max distance: " + pending.connection().position());
            return false;
        }
        LOGGER.info("Popped connection: " + pending);
        PlacedJigsawPiece childPiece = tryPlaceConnectingPiece(pending);
        if (childPiece != null) {
            addPiece(childPiece);
            queueConnections(childPiece, pending.depth() + 1);
            connectionRegistry.markConsumed(pending.connection().position());
            return true; // Work done
        } else {
            connectionRegistry.markSealed(pending.connection().position());
            if (listener != null) {
                listener.onConnectionFailed(pending.sourcePiece(), "Sealed: No valid connection found");
            }
        }
        return false;
    }

    /**
     * Handles connections that have exceeded the maximum depth. Attempts a "Soft Cap" using the
     * fallback pool. If that fails (or recurses), applies a "Hard Seal".
     */
    private boolean handlePanicMode(PendingJigsawConnection pending) {
        String fallbackPoolId = config.getFallbackPool();
        boolean softCapSuccess = false;
        if (fallbackPoolId != null && !"minecraft:empty".equals(fallbackPoolId)) {
            JigsawPool fallbackPool = poolRegistry.getPool(fallbackPoolId);
            if (fallbackPool != null) {
                String candidateId = fallbackPool.selectRandomElement(random);
                if (candidateId != null) {
                    NBTStructure.StructureData data = resolveStructureData(candidateId);
                    if (data != null) {
                        if (data.jigsawBlocks().isEmpty()) {
                            PlacedJigsawPiece capPiece = connectionFitter.tryFit(pending, List.of(candidateId), fallbackPoolId);
                            if (capPiece != null) {
                                addPiece(capPiece);
                                connectionRegistry.markConsumed(pending.connection().position());
                                usageTracker.recordPlacement(fallbackPoolId, capPiece.structureId());
                                softCapSuccess = true;
                                LOGGER.info("Panic Mode: Soft Cap placed at " + pending.connection().position());
                            }
                        } else {
                            LOGGER.warning("Panic Mode: Fallback piece " + candidateId
                                    + " has connections! Skipping to prevent infinite recursion.");
                        }
                    }
                }
            }
        }
        if (!softCapSuccess) {
            connectionRegistry.markSealed(pending.connection().position());
            LOGGER.info("Panic Mode: Hard Seal applied at " + pending.connection().position());
            return true;
        }
        return true;
    }

    private PlacedJigsawPiece tryPlaceConnectingPiece(PendingJigsawConnection pending) {
        attemptedConnections++;
        String targetPoolId = pending.getTargetPoolId();
        JigsawPool pool = poolRegistry.getPool(targetPoolId);
        if (pool == null) {
            LOGGER.warning("Target pool not found: " + targetPoolId);
            return null;
        }
        TerrainTrend trend = calculateTerrainTrend(pending);
        List<String> candidateIds = candidateSelector.selectCandidates(pool, 10, trend);
        if (candidateIds.isEmpty()) {
            LOGGER.warning("No candidates selected from pool: " + targetPoolId);
            return null;
        }
        // Fit Connection (Delegated)
        PlacedJigsawPiece result = connectionFitter.tryFit(pending, candidateIds, pool.getId());
        if (result != null) {
            successfulConnections++;
            usageTracker.recordPlacement(pool.getId(), result.structureId());
            // 3. Mark the child's connection as consumed
            // We need to find which connection on the new piece connected to the parent.
            // Logic: It must be adjacent (dist=1) and have a compatible orientation.
            Vector3Int parentPos = pending.connection().position();
            for (TransformedJigsawBlock conn : result.connections()) {
                // Check distance (Euclidean Squared distance of 1 block is 1.0)
                if (conn.position().distance(DistanceFunction.EuclideanSq, parentPos) <= 1.0) {
                    // Check orientation compatibility (e.g. North connects to South)
                    if (JigsawConnection.areOrientationsCompatible(pending.connection().orientation(), conn.orientation())) {
                        connectionRegistry.markConsumed(conn.position());
                        break;
                    }
                }
            }
        }
        return result;
    }

    private TerrainTrend calculateTerrainTrend(PendingJigsawConnection pending) {
        if ("none".equalsIgnoreCase(config.getTerrainAdaptation())) {
            return TerrainTrend.FLAT;
        }
        TransformedJigsawBlock conn = pending.connection();
        String orientation = conn.orientation();
        String primaryDir = orientation.toLowerCase().split("_")[0];
        int dx = 0;
        int dz = 0;
        switch (primaryDir) {
            case "north" -> dz = -1;
            case "south" -> dz = 1;
            case "east" -> dx = 1;
            case "west" -> dx = -1;
        }
        // Capture probe result for visualization
        int probeX = conn.position().getX() + (dx * StructurePlanner.PROBE_PROJECTION_DISTANCE);
        int probeZ = conn.position().getZ() + (dz * StructurePlanner.PROBE_PROJECTION_DISTANCE);
        Optional<Integer> surfaceYOpt = oracle.getSurfaceHeight(probeX, probeZ);
        TerrainTrend trend;
        if (surfaceYOpt.isEmpty()) {
            trend = TerrainTrend.FLAT;
        } else {
            int surfaceY = surfaceYOpt.get();
            int connY = conn.position().getY();
            int delta = surfaceY - connY;
            if (delta > 0) {
                trend = TerrainTrend.RISING;
            } else if (delta < 0) {
                trend = TerrainTrend.FALLING;
            } else {
                trend = TerrainTrend.FLAT;
            }
        }
        LOGGER.info(String.format("[Terrain Probe] %s | Conn Y: %d | Surface Y: %s",
                trend, conn.position().getY(), surfaceYOpt.map(Object::toString).orElse("N/A")));
        // Store the result for the blueprint
        this.lastProbeResult = new StructureBlueprint.ProbeResult(
                Vector3Int.of(probeX, surfaceYOpt.orElse(conn.position().getY()), probeZ),
                trend);
        return trend;
    }

    private PlacedJigsawPiece selectAndPlaceStartPiece(String startPoolId, Vector3Int startPos) {
        JigsawPool startPool = poolRegistry.getPool(startPoolId);
        if (startPool == null)
            return null;
        String structureId = startPool.selectRandomElement(random);
        if (structureId == null)
            return null;
        NBTStructure.StructureData structureData = resolveStructureData(structureId);
        if (structureData == null)
            return null;
        Rotation rotation = Rotation.values()[random.nextInt(Rotation.values().length)];
        List<TransformedJigsawBlock> connections = structureData.jigsawBlocks().stream()
                .map(j -> TransformUtil.transformJigsawConnection(j, startPos, rotation, structureData.size()))
                .collect(Collectors.toList());
        usageTracker.recordPlacement(startPool.getId(), structureId);
        return PlacedJigsawPiece.createStartPiece(structureId, startPos, rotation, structureData, connections, startPool.getId());
    }

    private NBTStructure.StructureData resolveStructureData(String structureId) {
        Optional<Structure> structureOpt = ResourceResolver.resolve(pack.getRegistry(Structure.class), structureId,
                pack.getRegistryKey().getID());
        if (structureOpt.isPresent() && structureOpt.get() instanceof JigsawProvider provider) {
            return provider.getStructureData();
        }
        return null;
    }

    private void addPiece(PlacedJigsawPiece piece) {
        pieces.add(piece);
        collisionDetector.add(piece.getWorldBounds());
        if (listener != null)
            listener.onPiecePlaced(piece);
    }

    private void queueConnections(PlacedJigsawPiece piece, int depth) {
        for (TransformedJigsawBlock connection : piece.connections()) {
            if (!connectionRegistry.isConsumed(connection.position())) {
                connectionQueue.offer(PendingJigsawConnection.create(connection, piece, depth));
            }
        }
    }

    private void prepareEnforcementQueue() {
        if (allConstraints.isEmpty())
            return;
        for (UsageConstraints constraint : allConstraints) {
            if (!constraint.hasMinimum())
                continue;
            int currentCount = usageTracker.getCount(constraint.poolId(), constraint.structureId());
            if (currentCount < constraint.minCount()) {
                pendingEnforcement.offer(constraint);
            }
        }
    }

    /**
     * Processes the next enforcement constraint.
     * 
     * @return true if a forced placement occurred (visual change), false if satisfied or skipped.
     */
    private boolean processNextEnforcement() {
        UsageConstraints constraint = pendingEnforcement.poll();
        if (constraint == null)
            return false;
        int currentCount = usageTracker.getCount(constraint.poolId(), constraint.structureId());
        if (currentCount >= constraint.minCount()) {
            LOGGER.info("Checked constraint: " + constraint + " [Satisfied]");
            return false;
        }
        if (enforcementStrategy == EnforcementStrategy.STRICT || enforcementStrategy == EnforcementStrategy.BEST_EFFORT) {
            ForcedPlacement.ForcedPlacementResult result = forcedPlacement.forcePlacementsForMinimum(
                    constraint, currentCount, pieces, collisionDetector);
            if (!result.pieces().isEmpty()) {
                for (PlacedJigsawPiece piece : result.pieces()) {
                    addPiece(piece);
                    usageTracker.recordPlacement(constraint.poolId(), piece.structureId());
                }
                for (ForcedPlacement.ConnectionUsage usage : result.consumedConnections()) {
                    connectionRegistry.markConsumed(usage.connectionPosition());
                }
                return true;
            }
            int finalCount = currentCount + result.pieces().size();
            if (finalCount < constraint.minCount()) {
                capturedViolations.add(new ConstraintViolation(constraint, finalCount, ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
            }
        } else if (enforcementStrategy == EnforcementStrategy.FLEXIBLE) {
            capturedViolations.add(new ConstraintViolation(constraint, currentCount, ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
        }
        return false;
    }

    private void checkStrictEnforcementFailure() {
        if (!capturedViolations.isEmpty() && enforcementStrategy == EnforcementStrategy.STRICT) {
            LOGGER.severe("Generation FAILED due to Strict Enforcement violations.");
            pieces.clear();
        }
    }

    public StructureBlueprint getBlueprint() {
        // Add defensive logging here
        if (pieces == null)
            LOGGER.severe("[Planner] Blueprint creation: pieces is NULL");
        if (capturedViolations == null)
            LOGGER.severe("[Planner] Blueprint creation: capturedViolations is NULL");
        return new StructureBlueprint(
                config.getID(),
                origin,
                new ArrayList<>(pieces),
                connectionRegistry.snapshot(),
                null,
                new ArrayList<>(capturedViolations),
                getStatistics(),
                sessionId,
                lastProbeResult);
    }

    private GenerationStatistics getStatistics() {
        long time = System.currentTimeMillis() - generationStartTime;
        Map<String, Integer> poolUsageSummary = usageTracker.getSnapshot().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().values().stream().mapToInt(Integer::intValue).sum()));
        return new GenerationStatistics(
                config.getID(), pieces.size(), pieces.stream().mapToInt(PlacedJigsawPiece::depth).max().orElse(0),
                time, poolUsageSummary, capturedViolations.stream().map(ConstraintViolation::getMessage).toList(),
                attemptedConnections, successfulConnections);
    }

    private List<UsageConstraints> gatherConstraintsFromPools() {
        try {
            return poolRegistry.getAllConstraints().stream()
                    .filter(c -> c.hasMinimum() || c.hasMaximum())
                    .sorted(Comparator.comparing(UsageConstraints::poolId).thenComparing(UsageConstraints::structureId))
                    .toList();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to gather constraints", e);
            return Collections.emptyList();
        }
    }
}