package com.ionsignal.minecraft.iongenesis.generation.enforcement;

import com.ionsignal.minecraft.iongenesis.generation.JigsawProvider;
import com.ionsignal.minecraft.iongenesis.generation.logic.JigsawConnection;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacementTransform;
import com.ionsignal.minecraft.iongenesis.generation.tracking.UsageConstraints;
import com.ionsignal.minecraft.iongenesis.model.geometry.AABB;
import com.ionsignal.minecraft.iongenesis.model.structure.JigsawData;
import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure;
import com.ionsignal.minecraft.iongenesis.util.JigsawUtils;
import com.ionsignal.minecraft.iongenesis.util.ResourceResolver;
import com.ionsignal.minecraft.iongenesis.util.TransformUtil;
import com.ionsignal.minecraft.iongenesis.util.SpatialMath;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.structure.Structure;

import com.dfsek.seismic.type.vector.Vector3Int;
import com.dfsek.seismic.type.Rotation;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ForcedPlacement {
    private static final Logger LOGGER = Logger.getLogger(ForcedPlacement.class.getName());
    private static final int MAX_FORCED_ATTEMPTS = 50;
    private static final List<Rotation> ALL_ROTATIONS = List.of(
            Rotation.NONE,
            Rotation.CW_90,
            Rotation.CW_180,
            Rotation.CCW_90);

    private final ConfigPack pack;
    private final RandomGenerator random;

    public ForcedPlacement(ConfigPack pack, long seed) {
        this.pack = pack;
        this.random = RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(seed ^ 0x5DEECE66DL);
    }

    /**
     * Helper to load StructureData from Registry.
     */
    private NBTStructure.StructureData loadStructureData(String id) {
        // Use ResourceResolver for consistent lookup strategy
        Optional<Structure> structureOpt = ResourceResolver.resolve(
                pack.getRegistry(Structure.class),
                id,
                pack.getRegistryKey().getID());
        if (structureOpt.isPresent() && structureOpt.get() instanceof JigsawProvider provider) {
            return provider.getStructureData();
        }
        return null;
    }

    public ForcedPlacementResult forcePlacementsForMinimum(
            UsageConstraints constraint,
            int currentCount,
            List<PlacedJigsawPiece> existingPieces,
            Set<AABB> occupiedSpace) {
        List<PlacedJigsawPiece> forcedPieces = new ArrayList<>();
        List<ConnectionUsage> consumedConnections = new ArrayList<>();
        int needed = constraint.minCount() - currentCount;
        if (needed <= 0) {
            return new ForcedPlacementResult(forcedPieces, consumedConnections);
        }
        LOGGER.info(String.format(
                "Attempting forced placement: need %d more pieces of %s from pool %s",
                needed,
                constraint.structureId(),
                constraint.poolId()));
        // Registry lookup
        NBTStructure.StructureData structureData = loadStructureData(constraint.structureId());
        if (structureData == null) {
            LOGGER.warning("Cannot force-place: structure not found in registry: " + constraint.structureId());
            return new ForcedPlacementResult(forcedPieces, consumedConnections);
        }
        List<ConnectionCandidate> candidates = findAvailableConnections(
                existingPieces,
                structureData);
        if (candidates.isEmpty()) {
            LOGGER.warning("Cannot force-place: no available connection points");
            return new ForcedPlacementResult(forcedPieces, consumedConnections);
        }
        JigsawUtils.shuffle(candidates, random);
        int attempts = 0;
        for (ConnectionCandidate candidate : candidates) {
            if (forcedPieces.size() >= needed) {
                break;
            }
            if (attempts++ >= MAX_FORCED_ATTEMPTS) {
                LOGGER.warning("Reached max forced placement attempts (" + MAX_FORCED_ATTEMPTS + ")");
                break;
            }
            PlacedJigsawPiece forced = tryForcePlacement(
                    candidate,
                    structureData,
                    constraint,
                    occupiedSpace);
            if (forced != null) {
                forcedPieces.add(forced);
                occupiedSpace.add(forced.getWorldBounds());
                consumedConnections.add(new ConnectionUsage(
                        candidate.parentPiece(),
                        candidate.connection().position()));
                LOGGER.fine("Forced placement successful at " + forced.worldPosition());
            }
        }
        int finalCount = currentCount + forcedPieces.size();
        if (finalCount < constraint.minCount()) {
            LOGGER.warning(String.format(
                    "Forced placement incomplete: placed %d of %d required pieces (total: %d/%d)",
                    forcedPieces.size(),
                    needed,
                    finalCount,
                    constraint.minCount()));
        } else {
            LOGGER.info(String.format(
                    "Forced placement successful: placed %d additional pieces (total: %d/%d)",
                    forcedPieces.size(),
                    finalCount,
                    constraint.minCount()));
        }
        return new ForcedPlacementResult(forcedPieces, consumedConnections);
    }

    private List<ConnectionCandidate> findAvailableConnections(
            List<PlacedJigsawPiece> pieces,
            NBTStructure.StructureData requiredStructure) {
        List<ConnectionCandidate> candidates = new ArrayList<>();
        for (PlacedJigsawPiece piece : pieces) {
            for (TransformedJigsawBlock connection : piece.connections()) {
                JigsawData.JigsawBlock parentJigsaw = connection.toJigsawBlock();
                for (JigsawData.JigsawBlock childJigsaw : requiredStructure.jigsawBlocks()) {
                    if (JigsawConnection.canConnect(parentJigsaw, childJigsaw)) {
                        candidates.add(new ConnectionCandidate(piece, connection));
                        break;
                    }
                }
            }
        }
        return candidates;
    }

    private PlacedJigsawPiece tryForcePlacement(
            ConnectionCandidate candidate,
            NBTStructure.StructureData structureData,
            UsageConstraints constraint,
            Set<AABB> occupiedSpace) {
        TransformedJigsawBlock parentConnection = candidate.connection();
        JigsawData.JigsawBlock parentJigsaw = parentConnection.toJigsawBlock();
        List<JigsawData.JigsawBlock> compatibleJigsaws = structureData.jigsawBlocks().stream()
                .filter(childJigsaw -> JigsawConnection.canConnect(parentJigsaw, childJigsaw))
                .toList();
        if (compatibleJigsaws.isEmpty()) {
            return null;
        }
        for (JigsawData.JigsawBlock childJigsaw : compatibleJigsaws) {
            JigsawData.JointType jointType = childJigsaw.info().jointType();
            if (jointType == JigsawData.JointType.ROLLABLE) {
                for (Rotation geometricRotation : ALL_ROTATIONS) {
                    JigsawData.JigsawBlock rotatedChildJigsaw = rotateJigsawBlock(
                            childJigsaw,
                            geometricRotation,
                            structureData.size());
                    PlacementTransform alignmentTransform = TransformUtil.calculateAlignment(
                            parentConnection,
                            rotatedChildJigsaw,
                            structureData.size());
                    Rotation finalRotation = combineRotations(geometricRotation, alignmentTransform.rotation());
                    AABB childBounds = AABB.fromPiece(
                            alignmentTransform.position(),
                            structureData.size(),
                            geometricRotation);
                    if (collides(childBounds, occupiedSpace)) {
                        continue;
                    }
                    List<TransformedJigsawBlock> connections = transformJigsawBlocks(
                            structureData.jigsawBlocks(),
                            alignmentTransform.position(),
                            finalRotation,
                            structureData.size());
                    int depth = candidate.parentPiece().depth() + 1;
                    return new PlacedJigsawPiece(
                            constraint.structureId(),
                            alignmentTransform.position(),
                            finalRotation,
                            structureData,
                            connections,
                            depth,
                            candidate.parentPiece(),
                            constraint.poolId());
                }
            } else {
                PlacementTransform alignmentTransform = TransformUtil.calculateAlignment(
                        parentConnection,
                        childJigsaw,
                        structureData.size());
                Rotation finalRotation = alignmentTransform.rotation();
                AABB childBounds = AABB.fromPiece(
                        alignmentTransform.position(),
                        structureData.size(),
                        Rotation.NONE);
                if (!collides(childBounds, occupiedSpace)) {
                    List<TransformedJigsawBlock> connections = transformJigsawBlocks(
                            structureData.jigsawBlocks(),
                            alignmentTransform.position(),
                            finalRotation,
                            structureData.size());

                    int depth = candidate.parentPiece().depth() + 1;
                    return new PlacedJigsawPiece(
                            constraint.structureId(),
                            alignmentTransform.position(),
                            finalRotation,
                            structureData,
                            connections,
                            depth,
                            candidate.parentPiece(),
                            constraint.poolId());
                }
            }
        }
        return null;
    }

    private static Rotation combineRotations(Rotation first, Rotation second) {
        if (first == Rotation.NONE)
            return second;
        if (second == Rotation.NONE)
            return first;
        int totalSteps = (rotationToSteps(first) + rotationToSteps(second)) % 4;
        return stepsToRotation(totalSteps);
    }

    private static int rotationToSteps(Rotation rotation) {
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

    private boolean collides(AABB bounds, Set<AABB> occupiedSpace) {
        for (AABB occupied : occupiedSpace) {
            if (bounds.intersects(occupied)) {
                return true;
            }
        }
        return false;
    }

    private JigsawData.JigsawBlock rotateJigsawBlock(
            JigsawData.JigsawBlock jigsaw,
            Rotation rotation,
            Vector3Int structureSize) {
        if (rotation == Rotation.NONE) {
            return jigsaw;
        }
        Vector3Int rotatedPos = SpatialMath.rotate(
                jigsaw.position(),
                rotation,
                structureSize);
        String rotatedOrientation = TransformUtil.rotateOrientation(
                jigsaw.orientation(),
                rotation);
        return new JigsawData.JigsawBlock(rotatedPos, rotatedOrientation, jigsaw.info());
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

    public record ForcedPlacementResult(
            List<PlacedJigsawPiece> pieces,
            List<ConnectionUsage> consumedConnections) {
    }

    public record ConnectionUsage(
            PlacedJigsawPiece parentPiece,
            Vector3Int connectionPosition) {
    }

    private record ConnectionCandidate(
            PlacedJigsawPiece parentPiece,
            TransformedJigsawBlock connection) {
    }
}