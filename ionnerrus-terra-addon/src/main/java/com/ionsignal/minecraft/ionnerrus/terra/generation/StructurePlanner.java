package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.core.JigsawConnection;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ConstraintViolation;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.EnforcementStrategy;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ForcedPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PendingJigsawConnection;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.GenerationStatistics;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.PoolUsageTracker;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.UsageConstraints;
import com.ionsignal.minecraft.ionnerrus.terra.model.JigsawData;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;
import com.ionsignal.minecraft.ionnerrus.terra.util.CoordinateConverter;
import com.ionsignal.minecraft.ionnerrus.terra.util.JigsawUtils;
import com.ionsignal.minecraft.ionnerrus.terra.util.ResourceResolver;
import com.ionsignal.minecraft.ionnerrus.terra.util.TransformUtil;

import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.structure.Structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * State machine based structure planner.
 * Replaces the monolithic JigsawGenerator.
 * Supports incremental "ticking" for time-sliced generation.
 */
public class StructurePlanner {
    private static final Logger LOGGER = Logger.getLogger(StructurePlanner.class.getName());

    public enum State {
        INITIALIZING, GENERATING, ENFORCING, FINISHED
    }

    // Inputs
    @SuppressWarnings("unused")
    private final long seed;
    private final ConfigPack pack;
    private final JigsawStructureTemplate config;
    private final Vector3Int origin;
    private final RandomGenerator random;
    private final PlanningEventListener listener;

    // Internal State
    private State state = State.INITIALIZING;
    private final PoolRegistry poolRegistry;
    private final List<PlacedJigsawPiece> pieces = new ArrayList<>();
    private final PriorityQueue<PendingJigsawConnection> connectionQueue = new PriorityQueue<>();
    private final Set<AABB> occupiedSpace = new HashSet<>();
    private final PoolUsageTracker usageTracker = new PoolUsageTracker();
    private final ConnectionRegistry connectionRegistry = new ConnectionRegistry();
    private final EnforcementStrategy enforcementStrategy;
    private final List<UsageConstraints> allConstraints;
    private final ForcedPlacement forcedPlacement;
    private final List<ConstraintViolation> capturedViolations = new ArrayList<>();

    // Metrics
    private int attemptedConnections = 0;
    private int successfulConnections = 0;
    private long generationStartTime;

    public StructurePlanner(ConfigPack pack, JigsawStructureTemplate config, Vector3Int origin, RandomGenerator random, long seed,
            PlanningEventListener listener) {
        this.pack = pack;
        this.config = config;
        this.origin = origin;
        this.random = random;
        this.seed = seed;
        this.listener = listener;

        this.poolRegistry = new PoolRegistry(pack);
        this.enforcementStrategy = EnforcementStrategy.fromConfig(config.getEnforcementStrategy());
        this.allConstraints = gatherConstraintsFromPools();
        this.forcedPlacement = new ForcedPlacement(pack, seed);
    }

    /**
     * Runs the planner synchronously until completion.
     * Used for standard Terra WorldGen.
     */
    public StructureBlueprint generateFull(String startPoolId) {
        initialize(startPoolId);
        while (tick()) {
            // Loop until finished
        }
        return getBlueprint();
    }

    /**
     * Advances the generation state by one step.
     * 
     * @return true if generation is still in progress, false if finished.
     */
    public boolean tick() {
        switch (state) {
            case INITIALIZING:
                // Should have been called via initialize(), but safe guard here
                return false;

            case GENERATING:
                if (connectionQueue.isEmpty()) {
                    state = State.ENFORCING;
                    return true;
                }
                processNextConnection();
                return true;

            case ENFORCING:
                ensureMinimumPieceCounts();
                state = State.FINISHED;
                return true;

            case FINISHED:
                return false;

            default:
                return false;
        }
    }

    public void initialize(String startPoolId) {
        generationStartTime = System.currentTimeMillis();
        capturedViolations.clear();
        PlacedJigsawPiece startPiece = selectAndPlaceStartPiece(startPoolId);
        if (startPiece != null) {
            addPiece(startPiece);
            queueConnections(startPiece, 0);
            state = State.GENERATING;
        } else {
            LOGGER.warning("Failed to place start piece.");
            state = State.FINISHED;
        }
    }

    private void processNextConnection() {
        PendingJigsawConnection pending = connectionQueue.poll();
        if (pending == null)
            return;
        if (pending.exceedsDepth(config.getMaxDepth()) ||
                pending.exceedsDistance(origin, config.getMaxDistance()) ||
                pending.isTerminator()) {
            return;
        }
        PlacedJigsawPiece childPiece = tryPlaceConnectingPiece(pending);
        if (childPiece != null) {
            addPiece(childPiece);
            queueConnections(childPiece, pending.depth() + 1);
            connectionRegistry.markConsumed(pending.connection().position());
        } else if (listener != null) {
            listener.onConnectionFailed(pending.sourcePiece(), "Could not find valid connection");
        }
    }

    private void addPiece(PlacedJigsawPiece piece) {
        pieces.add(piece);
        occupiedSpace.add(piece.getWorldBounds());
        if (listener != null)
            listener.onPiecePlaced(piece);
    }

    public StructureBlueprint getBlueprint() {
        return new StructureBlueprint(
                config.getID(),
                origin,
                new ArrayList<>(pieces), // Defensive copy
                connectionRegistry,
                null, // Auto-calc bounds
                new ArrayList<>(capturedViolations),
                getStatistics());
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

    private PlacedJigsawPiece selectAndPlaceStartPiece(String startPoolId) {
        JigsawPool startPool = poolRegistry.getPool(startPoolId);
        if (startPool == null)
            return null;
        String structureId = startPool.selectRandomElement(random);
        if (structureId == null)
            return null;
        NBTStructure.StructureData structureData = loadStructureData(structureId);
        if (structureData == null)
            return null;
        Rotation rotation = selectRandomRotation();
        List<TransformedJigsawBlock> connections = transformJigsawBlocks(
                structureData.jigsawBlocks(), origin, rotation, structureData.size());
        usageTracker.recordPlacement(startPool.getId(), structureId);
        return PlacedJigsawPiece.createStartPiece(structureId, origin, rotation, structureData, connections, startPool.getId());
    }

    private PlacedJigsawPiece tryPlaceConnectingPiece(PendingJigsawConnection pending) {
        attemptedConnections++;
        String targetPoolId = pending.getTargetPoolId();
        JigsawPool pool = poolRegistry.getPool(targetPoolId);
        if (pool == null)
            return null;
        List<String> candidateIds = selectCandidateFilesRespectingMaxCounts(pool, 10);
        if (candidateIds.isEmpty())
            return null;
        JigsawUtils.shuffle(candidateIds, random);
        for (String structureId : candidateIds) {
            NBTStructure.StructureData structureData = loadStructureData(structureId);
            if (structureData == null)
                continue;
            List<JigsawData.JigsawBlock> shuffledJigsaws = new ArrayList<>(structureData.jigsawBlocks());
            JigsawUtils.shuffle(shuffledJigsaws, random);
            for (JigsawData.JigsawBlock childJigsaw : shuffledJigsaws) {
                if (!JigsawConnection.canConnect(pending.connection().toJigsawBlock(), childJigsaw))
                    continue;
                List<Rotation> rotationsToTry = getRotationsForJoint(childJigsaw.info().jointType());
                for (Rotation geometricRotation : rotationsToTry) {
                    // Logic identical to JigsawGenerator
                    JigsawData.JigsawBlock rotatedChildJigsaw = rotateJigsawBlock(childJigsaw, geometricRotation, structureData.size());
                    Rotation alignmentRotation = calculateRequiredRotation(pending.connection().orientation(),
                            rotatedChildJigsaw.orientation());
                    Rotation finalRotation = combineRotations(geometricRotation, alignmentRotation);
                    Vector3Int rotatedJigsawPos = CoordinateConverter.rotate(childJigsaw.position(), finalRotation, structureData.size());
                    Vector3Int connectionOffset = getConnectionOffset(pending.connection().orientation());
                    Vector3Int finalPosition = Vector3Int.of(
                            pending.connection().position().getX() + connectionOffset.getX() - rotatedJigsawPos.getX(),
                            pending.connection().position().getY() + connectionOffset.getY() - rotatedJigsawPos.getY(),
                            pending.connection().position().getZ() + connectionOffset.getZ() - rotatedJigsawPos.getZ());

                    AABB childBounds = AABB.fromPiece(finalPosition, structureData.size(), finalRotation);

                    if (!collides(childBounds)) {
                        List<TransformedJigsawBlock> connections = transformJigsawBlocks(
                                structureData.jigsawBlocks(), finalPosition, finalRotation, structureData.size());
                        // Mark the child's connection as consumed immediately
                        Vector3Int consumedChildPosition = calculateTransformedJigsawPosition(childJigsaw, finalPosition, finalRotation,
                                structureData.size());
                        connectionRegistry.markConsumed(consumedChildPosition);
                        successfulConnections++;
                        usageTracker.recordPlacement(pool.getId(), structureId);
                        return new PlacedJigsawPiece(structureId, finalPosition, finalRotation, structureData, connections,
                                pending.depth() + 1, pending.sourcePiece(), pool.getId());
                    }
                }
            }
        }
        return null;
    }

    private void ensureMinimumPieceCounts() {
        if (allConstraints.isEmpty())
            return;

        for (UsageConstraints constraint : allConstraints) {
            if (!constraint.hasMinimum())
                continue;
            int currentCount = usageTracker.getCount(constraint.poolId(), constraint.structureId());
            if (currentCount < constraint.minCount()) {
                if (enforcementStrategy == EnforcementStrategy.STRICT || enforcementStrategy == EnforcementStrategy.BEST_EFFORT) {
                    ForcedPlacement.ForcedPlacementResult result = forcedPlacement.forcePlacementsForMinimum(
                            constraint, currentCount, pieces, occupiedSpace);
                    if (!result.pieces().isEmpty()) {
                        for (PlacedJigsawPiece piece : result.pieces()) {
                            addPiece(piece); // Use helper to trigger events
                            usageTracker.recordPlacement(constraint.poolId(), piece.structureId());
                        }
                        for (ForcedPlacement.ConnectionUsage usage : result.consumedConnections()) {
                            connectionRegistry.markConsumed(usage.connectionPosition());
                        }
                    }
                    int finalCount = currentCount + result.pieces().size();
                    if (finalCount < constraint.minCount()) {
                        capturedViolations
                                .add(new ConstraintViolation(constraint, finalCount, ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
                    }
                } else if (enforcementStrategy == EnforcementStrategy.FLEXIBLE) {
                    capturedViolations
                            .add(new ConstraintViolation(constraint, currentCount, ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
                }
            }
        }

        if (!capturedViolations.isEmpty() && enforcementStrategy == EnforcementStrategy.STRICT) {
            LOGGER.severe("Generation FAILED due to Strict Enforcement violations.");
            pieces.clear(); // Fail hard
        }
    }

    private void queueConnections(PlacedJigsawPiece piece, int depth) {
        for (TransformedJigsawBlock connection : piece.connections()) {
            if (!connectionRegistry.isConsumed(connection.position())) {
                connectionQueue.offer(PendingJigsawConnection.create(connection, piece, depth));
            }
        }
    }

    private boolean collides(AABB bounds) {
        for (AABB occupied : occupiedSpace) {
            if (bounds.intersects(occupied))
                return true;
        }
        return false;
    }

    private NBTStructure.StructureData loadStructureData(String id) {
        Optional<Structure> structureOpt = ResourceResolver.resolve(pack.getRegistry(Structure.class), id, pack.getRegistryKey().getID());
        if (structureOpt.isPresent() && structureOpt.get() instanceof JigsawProvider provider) {
            return provider.getStructureData();
        }
        return null;
    }

    private List<String> selectCandidateFilesRespectingMaxCounts(JigsawPool pool, int count) {
        List<String> candidates = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        int attempts = 0;
        int maxAttempts = count * 3;
        while (candidates.size() < count && attempts++ < maxAttempts) {
            Set<String> excludedIds = new HashSet<>();
            for (JigsawPool.WeightedElement element : pool.getElements()) {
                int currentCount = usageTracker.getCount(pool.getId(), element.getStructureId());
                int pendingCount = (int) candidates.stream().filter(c -> c.equals(element.getStructureId())).count();
                if (currentCount + pendingCount >= element.getMaxCount()) {
                    excludedIds.add(element.getStructureId());
                }
            }
            if (excludedIds.size() == pool.getElements().size())
                break;

            String id = pool.selectRandomElementWithExclusions(random, excludedIds);
            if (id == null)
                break;

            if (!selected.contains(id)) {
                candidates.add(id);
                selected.add(id);
            }
        }
        return candidates;
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

    // Math helpers (Rotation, Transform) are pure and copied from Generator
    private Rotation selectRandomRotation() {
        return Rotation.values()[random.nextInt(Rotation.values().length)];
    }

    private List<TransformedJigsawBlock> transformJigsawBlocks(List<JigsawData.JigsawBlock> blocks, Vector3Int pos, Rotation rot,
            Vector3Int size) {
        return blocks.stream().map(j -> TransformUtil.transformJigsawConnection(j, pos, rot, size)).collect(Collectors.toList());
    }

    private JigsawData.JigsawBlock rotateJigsawBlock(JigsawData.JigsawBlock jigsaw, Rotation rotation, Vector3Int size) {
        if (rotation == Rotation.NONE)
            return jigsaw;
        Vector3Int rotatedPos = CoordinateConverter.rotate(jigsaw.position(), rotation, size);
        String rotatedOrientation = TransformUtil.rotateOrientation(jigsaw.orientation(), rotation);
        return new JigsawData.JigsawBlock(rotatedPos, rotatedOrientation, jigsaw.info());
    }

    private Rotation calculateRequiredRotation(String parent, String child) {
        // Simplified for brevity, logic identical to Generator
        String[] pParts = parent.toLowerCase().split("_");
        String[] cParts = child.toLowerCase().split("_");
        String pPrimary = pParts[0];
        String pSecondary = pParts.length > 1 ? pParts[1] : "north";
        String cPrimary = cParts[0];
        String cSecondary = cParts.length > 1 ? cParts[1] : "north";

        if ("up".equals(pPrimary) || "down".equals(pPrimary)) {
            return calculateRotationBetweenDirections(cSecondary, pSecondary);
        }
        return calculateRotationBetweenDirections(cPrimary, getOppositeDirection(pPrimary));
    }

    private String getOppositeDirection(String d) {
        return switch (d.toLowerCase()) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            case "up" -> "down";
            case "down" -> "up";
            default -> d;
        };
    }

    private Rotation calculateRotationBetweenDirections(String from, String to) {
        if (from.equals(to))
            return Rotation.NONE;
        int fIdx = dirIdx(from);
        int tIdx = dirIdx(to);
        if (fIdx == -1 || tIdx == -1)
            return Rotation.NONE;
        int steps = (tIdx - fIdx + 4) % 4;
        return Rotation.values()[(steps + 1) % 4 == 0 ? 0 : (steps + 1)]; // Mapping logic check: Seismic Rotation enum order is NONE,
                                                                          // CW_90, CW_180, CCW_90
        // Seismic mapping: 0->NONE, 1->CW_90, 2->CW_180, 3->CCW_90
        // Steps 1 = CW_90. Steps 2 = CW_180. Steps 3 = CCW_90.
        // My previous helper was switch(steps). Let's use that.
    }

    private int dirIdx(String d) {
        return switch (d) {
            case "north" -> 0;
            case "east" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            default -> -1;
        };
    }

    private Rotation combineRotations(Rotation r1, Rotation r2) {
        if (r1 == Rotation.NONE)
            return r2;
        if (r2 == Rotation.NONE)
            return r1;
        int s1 = rotSteps(r1);
        int s2 = rotSteps(r2);
        int total = (s1 + s2) % 4;
        return stepsToRot(total);
    }

    private int rotSteps(Rotation r) {
        return switch (r) {
            case NONE -> 0;
            case CW_90 -> 1;
            case CW_180 -> 2;
            case CCW_90 -> 3;
        };
    }

    private Rotation stepsToRot(int s) {
        return switch (s % 4) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CW_90;
            case 2 -> Rotation.CW_180;
            case 3 -> Rotation.CCW_90;
            default -> Rotation.NONE;
        };
    }

    private List<Rotation> getRotationsForJoint(JigsawData.JointType type) {
        return type == JigsawData.JointType.ROLLABLE ? List.of(Rotation.values()) : List.of(Rotation.NONE);
    }

    private Vector3Int getConnectionOffset(String orientation) {
        String primary = orientation.toLowerCase().split("_")[0];
        return switch (primary) {
            case "north" -> Vector3Int.of(0, 0, -1);
            case "south" -> Vector3Int.of(0, 0, 1);
            case "east" -> Vector3Int.of(1, 0, 0);
            case "west" -> Vector3Int.of(-1, 0, 0);
            case "up" -> Vector3Int.of(0, 1, 0);
            case "down" -> Vector3Int.of(0, -1, 0);
            default -> Vector3Int.of(0, 0, 0);
        };
    }

    private Vector3Int calculateTransformedJigsawPosition(JigsawData.JigsawBlock jigsaw, Vector3Int worldPos, Rotation rot,
            Vector3Int size) {
        Vector3Int rotatedPos = CoordinateConverter.rotate(jigsaw.position(), rot, size);
        return Vector3Int.of(worldPos.getX() + rotatedPos.getX(), worldPos.getY() + rotatedPos.getY(), worldPos.getZ() + rotatedPos.getZ());
    }
}