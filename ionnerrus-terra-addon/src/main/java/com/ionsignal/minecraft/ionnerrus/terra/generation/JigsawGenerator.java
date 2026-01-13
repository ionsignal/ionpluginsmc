package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.core.JigsawConnection;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ConstraintViolation;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.EnforcementStrategy;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ForcedPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.JigsawPlacement;
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
import com.ionsignal.minecraft.ionnerrus.terra.util.TransformUtil;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.random.RandomGenerator;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class JigsawGenerator {
    private static final Logger LOGGER = Logger.getLogger(JigsawGenerator.class.getName());

    private final ConfigPack pack;
    private final Platform platform;
    private final RandomGenerator random;
    private final JigsawStructureTemplate config;
    private final Vector3Int origin;
    private final PoolRegistry poolRegistry;
    private final List<PlacedJigsawPiece> pieces;
    private final PriorityQueue<PendingJigsawConnection> connectionQueue;
    private final Set<AABB> occupiedSpace;
    private final PoolUsageTracker usageTracker;
    private final ConnectionRegistry connectionRegistry;
    private final EnforcementStrategy enforcementStrategy;
    private final List<UsageConstraints> allConstraints;
    private final ForcedPlacement forcedPlacement;
    private final List<ConstraintViolation> capturedViolations = new ArrayList<>();

    private int attemptedConnections;
    private int successfulConnections;
    private long generationStartTime;

    public JigsawGenerator(
            ConfigPack pack,
            Platform platform,
            JigsawStructureTemplate config,
            Vector3Int origin,
            RandomGenerator random,
            long seedForForcedPlacement) {
        this.pack = pack;
        this.platform = platform;
        this.config = config;
        this.origin = origin;
        this.random = random;
        this.poolRegistry = new PoolRegistry(pack);
        this.pieces = new ArrayList<>();
        this.connectionQueue = new PriorityQueue<>();
        this.occupiedSpace = new HashSet<>();
        this.usageTracker = new PoolUsageTracker();
        this.connectionRegistry = new ConnectionRegistry();
        this.attemptedConnections = 0;
        this.successfulConnections = 0;
        this.enforcementStrategy = EnforcementStrategy.fromConfig(config.getEnforcementStrategy());
        this.allConstraints = gatherConstraintsFromPools();
        if (enforcementStrategy != EnforcementStrategy.BEST_EFFORT && allConstraints.isEmpty()) {
            LOGGER.warning(String.format(
                    "Enforcement strategy is '%s' but no constraints were found.",
                    enforcementStrategy.getConfigValue()));
        }
        this.forcedPlacement = new ForcedPlacement(pack, seedForForcedPlacement);
        if (!allConstraints.isEmpty()) {
            long minConstraints = allConstraints.stream().filter(UsageConstraints::hasMinimum).count();
            long maxConstraints = allConstraints.stream().filter(UsageConstraints::hasMaximum).count();
            LOGGER.info(String.format(
                    "Generator initialized with enforcement strategy: %s, tracking %d constraints (%d min, %d max)",
                    enforcementStrategy.getConfigValue(),
                    allConstraints.size(),
                    minConstraints,
                    maxConstraints));
        }
    }

    private List<UsageConstraints> gatherConstraintsFromPools() {
        try {
            List<UsageConstraints> constraints = poolRegistry.getAllConstraints();
            if (constraints.isEmpty()) {
                LOGGER.fine("No constraints found in pool registry...");
                return Collections.emptyList();
            }
            List<UsageConstraints> filtered = constraints.stream()
                    .filter(c -> c.hasMinimum() || c.hasMaximum())
                    .toList();
            List<UsageConstraints> sorted = filtered.stream()
                    .sorted((c1, c2) -> {
                        int poolCompare = c1.poolId().compareTo(c2.poolId());
                        if (poolCompare != 0) {
                            return poolCompare;
                        }
                        return c1.structureId().compareTo(c2.structureId());
                    })
                    .toList();
            LOGGER.info(String.format(
                    "Gathered and sorted %d constraints (deterministic order ensured)",
                    sorted.size()));
            return sorted;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to gather constraints from pool registry...",
                    e);
            return Collections.emptyList();
        }
    }

    public JigsawPlacement generate(String startPoolId) {
        generationStartTime = System.currentTimeMillis();
        capturedViolations.clear();
        PlacedJigsawPiece startPiece = selectAndPlaceStartPiece(startPoolId);
        if (startPiece == null) {
            LOGGER.warning("Failed to place start piece from pool: " + startPoolId);
            return JigsawPlacement.empty(origin, config.getID(), connectionRegistry);
        }
        pieces.add(startPiece);
        occupiedSpace.add(startPiece.getWorldBounds());
        queueConnections(startPiece, 0);
        while (!connectionQueue.isEmpty()) {
            PendingJigsawConnection pending = connectionQueue.poll();
            if (pending.exceedsDepth(config.getMaxDepth())) {
                continue;
            }
            if (pending.exceedsDistance(origin, config.getMaxDistance())) {
                continue;
            }
            if (pending.isTerminator()) {
                continue;
            }
            PlacedJigsawPiece childPiece = tryPlaceConnectingPiece(pending);
            if (childPiece != null) {
                pieces.add(childPiece);
                occupiedSpace.add(childPiece.getWorldBounds());
                queueConnections(childPiece, pending.depth() + 1);
                connectionRegistry.markConsumed(pending.connection().position());
            }
        }
        ensureMinimumPieceCounts();
        long generationTime = System.currentTimeMillis() - generationStartTime;
        LOGGER.info(String.format(
                "Generation completed on platform %s in %dms: %d pieces placed, %d/%d connections successful%s",
                platform.platformName(),
                generationTime,
                pieces.size(),
                successfulConnections,
                attemptedConnections,
                capturedViolations.isEmpty() ? "" : " (" + capturedViolations.size() + " violations)"));
        return new JigsawPlacement(pieces, origin, config.getID(), connectionRegistry);
    }

    private Vector3Int calculateTransformedJigsawPosition(
            JigsawData.JigsawBlock jigsaw,
            Vector3Int worldPosition,
            Rotation rotation,
            Vector3Int structureSize) {
        Vector3Int rotatedPos = CoordinateConverter.rotate(jigsaw.position(), rotation, structureSize);
        return Vector3Int.of(
                worldPosition.getX() + rotatedPos.getX(),
                worldPosition.getY() + rotatedPos.getY(),
                worldPosition.getZ() + rotatedPos.getZ());
    }

    /**
     * Helper to load StructureData from Registry.
     */
    private NBTStructure.StructureData loadStructureData(String id) {
        Optional<Structure> structureOpt;
        if (id.contains(":")) {
            structureOpt = pack.getRegistry(Structure.class).get(RegistryKey.parse(id));
        } else {
            structureOpt = pack.getRegistry(Structure.class).getByID(id);
        }
        if (structureOpt.isPresent()) {
            Structure structure = structureOpt.get();
            if (structure instanceof JigsawProvider provider) {
                return provider.getStructureData();
            } else {
                LOGGER.warning("Structure '" + id + "' is not a JigsawProvider (NBTStructure). Cannot be used in jigsaw generation.");
            }
        } else {
            LOGGER.warning("Structure not found in registry: " + id);
        }
        return null;
    }

    private PlacedJigsawPiece selectAndPlaceStartPiece(String startPoolId) {
        JigsawPool startPool = poolRegistry.getPool(startPoolId);
        if (startPool == null) {
            LOGGER.severe("Start pool not found: " + startPoolId);
            return null;
        }
        String structureId = startPool.selectRandomElement(random);
        if (structureId == null) {
            LOGGER.warning("Start pool is empty: " + startPoolId);
            return null;
        }
        // Use registry lookup instead of file loading
        NBTStructure.StructureData structureData = loadStructureData(structureId);
        if (structureData == null) {
            return null;
        }
        Rotation rotation = selectRandomRotation();
        List<TransformedJigsawBlock> connections = transformJigsawBlocks(
                structureData.jigsawBlocks(),
                origin,
                rotation,
                structureData.size());
        usageTracker.recordPlacement(startPool.getId(), structureId);
        return PlacedJigsawPiece.createStartPiece(structureId,
                origin,
                rotation,
                structureData,
                connections,
                startPool.getId());
    }

    private PlacedJigsawPiece tryPlaceConnectingPiece(PendingJigsawConnection pending) {
        attemptedConnections++;
        String targetPoolId = pending.getTargetPoolId();
        JigsawPool pool = poolRegistry.getPool(targetPoolId);
        if (pool == null) {
            LOGGER.warning("Target pool not found: " + targetPoolId);
            return null;
        }
        List<String> candidateIds = selectCandidateFilesRespectingMaxCounts(pool, 10);
        if (candidateIds.isEmpty()) {
            return null;
        }
        JigsawUtils.shuffle(candidateIds, random);
        for (String structureId : candidateIds) {
            // Registry lookup
            NBTStructure.StructureData structureData = loadStructureData(structureId);
            if (structureData == null) {
                continue;
            }
            List<JigsawData.JigsawBlock> shuffledJigsaws = new ArrayList<>(structureData.jigsawBlocks());
            JigsawUtils.shuffle(shuffledJigsaws, random);
            for (JigsawData.JigsawBlock childJigsaw : shuffledJigsaws) {
                if (!JigsawConnection.canConnect(pending.connection().toJigsawBlock(), childJigsaw)) {
                    continue;
                }
                List<Rotation> rotationsToTry = getRotationsForJoint(childJigsaw.info().jointType());
                for (Rotation geometricRotation : rotationsToTry) {
                    JigsawData.JigsawBlock rotatedChildJigsaw = rotateJigsawBlock(
                            childJigsaw,
                            geometricRotation,
                            structureData.size());
                    Rotation alignmentRotation = calculateRequiredRotation(
                            pending.connection().orientation(),
                            rotatedChildJigsaw.orientation());
                    Rotation finalRotation = combineRotations(geometricRotation, alignmentRotation);
                    Vector3Int rotatedJigsawPos = CoordinateConverter.rotate(
                            childJigsaw.position(),
                            finalRotation,
                            structureData.size());
                    Vector3Int connectionOffset = getConnectionOffset(pending.connection().orientation());
                    Vector3Int finalPosition = Vector3Int.of(
                            pending.connection().position().getX() + connectionOffset.getX() - rotatedJigsawPos.getX(),
                            pending.connection().position().getY() + connectionOffset.getY() - rotatedJigsawPos.getY(),
                            pending.connection().position().getZ() + connectionOffset.getZ() - rotatedJigsawPos.getZ());
                    AABB childBounds = AABB.fromPiece(
                            finalPosition,
                            structureData.size(),
                            finalRotation);
                    boolean hasCollision = collides(childBounds);
                    if (!hasCollision) {
                        List<TransformedJigsawBlock> connections = transformJigsawBlocks(
                                structureData.jigsawBlocks(),
                                finalPosition,
                                finalRotation,
                                structureData.size());
                        Vector3Int consumedChildPosition = calculateTransformedJigsawPosition(
                                childJigsaw,
                                finalPosition,
                                finalRotation,
                                structureData.size());
                        connectionRegistry.markConsumed(consumedChildPosition);
                        successfulConnections++;
                        usageTracker.recordPlacement(pool.getId(), structureId);
                        return new PlacedJigsawPiece(
                                structureId,
                                finalPosition,
                                finalRotation,
                                structureData,
                                connections,
                                pending.depth() + 1,
                                pending.sourcePiece(),
                                pool.getId());
                    }
                }
            }
        }
        return null;
    }

    private JigsawData.JigsawBlock rotateJigsawBlock(
            JigsawData.JigsawBlock jigsaw,
            Rotation rotation,
            Vector3Int structureSize) {
        if (rotation == Rotation.NONE) {
            return jigsaw;
        }
        Vector3Int rotatedPos = CoordinateConverter.rotate(
                jigsaw.position(),
                rotation,
                structureSize);
        String rotatedOrientation = TransformUtil.rotateOrientation(
                jigsaw.orientation(),
                rotation);
        return new JigsawData.JigsawBlock(rotatedPos, rotatedOrientation, jigsaw.info());
    }

    private Rotation calculateRequiredRotation(String parentOrientation, String childOrientation) {
        String[] parentParts = parentOrientation.toLowerCase().split("_");
        String parentPrimary = parentParts[0];
        String parentSecondary = parentParts.length > 1 ? parentParts[1] : "north";
        String[] childParts = childOrientation.toLowerCase().split("_");
        String childPrimary = childParts[0];
        String childSecondary = childParts.length > 1 ? childParts[1] : "north";
        if ("up".equals(parentPrimary) || "down".equals(parentPrimary)) {
            return calculateRotationBetweenDirections(childSecondary, parentSecondary);
        }
        String targetPrimary = getOppositeDirection(parentPrimary);
        return calculateRotationBetweenDirections(childPrimary, targetPrimary);
    }

    private String getOppositeDirection(String direction) {
        return switch (direction.toLowerCase()) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            case "up" -> "down";
            case "down" -> "up";
            default -> direction;
        };
    }

    private Rotation calculateRotationBetweenDirections(String from, String to) {
        if (from.equals(to)) {
            return Rotation.NONE;
        }
        int fromIndex = directionToIndex(from);
        int toIndex = directionToIndex(to);
        if (fromIndex == -1 || toIndex == -1) {
            return Rotation.NONE;
        }
        int steps = (toIndex - fromIndex + 4) % 4;
        return stepsToRotation(steps);
    }

    private int directionToIndex(String direction) {
        return switch (direction.toLowerCase()) {
            case "north" -> 0;
            case "east" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            default -> -1;
        };
    }

    private Vector3Int getConnectionOffset(String parentOrientation) {
        String primaryDir = parentOrientation.toLowerCase().split("_")[0];
        return switch (primaryDir) {
            case "north" -> Vector3Int.of(0, 0, -1);
            case "south" -> Vector3Int.of(0, 0, 1);
            case "east" -> Vector3Int.of(1, 0, 0);
            case "west" -> Vector3Int.of(-1, 0, 0);
            case "up" -> Vector3Int.of(0, 1, 0);
            case "down" -> Vector3Int.of(0, -1, 0);
            default -> Vector3Int.of(0, 0, 0);
        };
    }

    private Rotation combineRotations(Rotation first, Rotation second) {
        if (first == Rotation.NONE)
            return second;
        if (second == Rotation.NONE)
            return first;
        int totalSteps = (rotationToSteps(first) + rotationToSteps(second)) % 4;
        return stepsToRotation(totalSteps);
    }

    private int rotationToSteps(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CW_90 -> 1;
            case CW_180 -> 2;
            case CCW_90 -> 3;
        };
    }

    private static Rotation stepsToRotation(int steps) {
        return switch (steps % 4) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CW_90;
            case 2 -> Rotation.CW_180;
            case 3 -> Rotation.CCW_90;
            default -> Rotation.NONE;
        };
    }

    private List<Rotation> getRotationsForJoint(JigsawData.JointType jointType) {
        if (jointType == JigsawData.JointType.ROLLABLE) {
            return List.of(Rotation.NONE, Rotation.CW_90, Rotation.CW_180, Rotation.CCW_90);
        } else {
            return List.of(Rotation.NONE);
        }
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
                int pendingCount = (int) candidates.stream()
                        .filter(c -> c.equals(element.getStructureId()))
                        .count();
                if (currentCount + pendingCount >= element.getMaxCount()) {
                    excludedIds.add(element.getStructureId());
                }
            }
            if (excludedIds.size() == pool.getElements().size()) {
                break;
            }
            String id = pool.selectRandomElementWithExclusions(random, excludedIds); // CHANGE
            if (id == null) {
                break;
            }
            if (!selected.contains(id)) {
                candidates.add(id);
                selected.add(id);
            }
        }
        return candidates;
    }

    private void ensureMinimumPieceCounts() {
        if (allConstraints.isEmpty()) {
            return;
        }
        for (UsageConstraints constraint : allConstraints) {
            if (!constraint.hasMinimum()) {
                continue;
            }
            int currentCount = usageTracker.getCount(constraint.poolId(), constraint.structureId());
            if (currentCount < constraint.minCount()) {
                switch (enforcementStrategy) {
                    case STRICT:
                        handleStrictEnforcement(constraint, currentCount);
                        break;
                    case BEST_EFFORT:
                        handleBestEffortEnforcement(constraint, currentCount);
                        break;
                    case FLEXIBLE:
                        handleFlexibleEnforcement(constraint, currentCount);
                        break;
                }
            }
        }
        if (!capturedViolations.isEmpty() && enforcementStrategy == EnforcementStrategy.STRICT) {
            LOGGER.severe("Generation FAILED due to " + capturedViolations.size() + " constraint violations:");
            pieces.clear();
        }
    }

    private void handleStrictEnforcement(UsageConstraints constraint, int currentCount) {
        ForcedPlacement.ForcedPlacementResult result = forcedPlacement.forcePlacementsForMinimum(
                constraint,
                currentCount,
                pieces,
                occupiedSpace);
        int finalCount = currentCount + result.pieces().size();
        if (finalCount < constraint.minCount()) {
            capturedViolations.add(new ConstraintViolation(
                    constraint,
                    finalCount,
                    ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
        } else {
            pieces.addAll(result.pieces());
            for (ForcedPlacement.ConnectionUsage usage : result.consumedConnections()) {
                connectionRegistry.markConsumed(usage.connectionPosition());
            }
            for (PlacedJigsawPiece piece : result.pieces()) {
                usageTracker.recordPlacement(constraint.poolId(), piece.structureId());
            }
        }
    }

    private void handleBestEffortEnforcement(UsageConstraints constraint, int currentCount) {
        ForcedPlacement.ForcedPlacementResult result = forcedPlacement.forcePlacementsForMinimum(
                constraint,
                currentCount,
                pieces,
                occupiedSpace);
        if (!result.pieces().isEmpty()) {
            pieces.addAll(result.pieces());
            for (ForcedPlacement.ConnectionUsage usage : result.consumedConnections()) {
                connectionRegistry.markConsumed(usage.connectionPosition());
            }
            for (PlacedJigsawPiece piece : result.pieces()) {
                usageTracker.recordPlacement(constraint.poolId(), piece.structureId());
            }
        }
        int finalCount = currentCount + result.pieces().size();
        if (finalCount < constraint.minCount()) {
            capturedViolations.add(new ConstraintViolation(
                    constraint,
                    finalCount,
                    ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
        }
    }

    private void handleFlexibleEnforcement(UsageConstraints constraint, int currentCount) {
        if (currentCount < constraint.minCount()) {
            capturedViolations.add(new ConstraintViolation(
                    constraint,
                    currentCount,
                    ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
        }
    }

    private void queueConnections(PlacedJigsawPiece piece, int depth) {
        for (TransformedJigsawBlock connection : piece.connections()) {
            if (!connectionRegistry.isConsumed(connection.position())) {
                PendingJigsawConnection pending = PendingJigsawConnection.create(
                        connection,
                        piece,
                        depth);
                connectionQueue.offer(pending);
            }
        }
    }

    private List<TransformedJigsawBlock> transformJigsawBlocks(
            List<JigsawData.JigsawBlock> jigsawBlocks,
            Vector3Int worldPosition,
            Rotation rotation,
            Vector3Int structureSize) {
        return jigsawBlocks.stream()
                .map(jigsaw -> TransformUtil.transformJigsawConnection(
                        jigsaw,
                        worldPosition,
                        rotation,
                        structureSize))
                .collect(Collectors.toList());
    }

    private boolean collides(AABB bounds) {
        for (AABB occupied : occupiedSpace) {
            if (bounds.intersects(occupied)) {
                return true;
            }
        }
        return false;
    }

    private Rotation selectRandomRotation() {
        Rotation[] rotations = Rotation.values();
        return rotations[random.nextInt(rotations.length)];
    }

    public GenerationStatistics getStatistics() {
        List<String> violationMessages = capturedViolations.stream()
                .map(ConstraintViolation::getMessage)
                .toList();
        long generationTime = System.currentTimeMillis() - generationStartTime;
        Map<String, Integer> poolUsageSummary = usageTracker.getSnapshot().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().values().stream().mapToInt(Integer::intValue).sum()));
        return new GenerationStatistics(
                config.getID(),
                pieces.size(),
                pieces.stream().mapToInt(PlacedJigsawPiece::depth).max().orElse(0),
                generationTime,
                poolUsageSummary,
                violationMessages,
                attemptedConnections,
                successfulConnections);
    }

    public PoolUsageTracker getUsageTracker() {
        return usageTracker;
    }
}