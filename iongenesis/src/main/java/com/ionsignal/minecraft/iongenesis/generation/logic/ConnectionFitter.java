package com.ionsignal.minecraft.iongenesis.generation.logic;

import com.ionsignal.minecraft.iongenesis.generation.JigsawProvider;
import com.ionsignal.minecraft.iongenesis.generation.placements.PendingJigsawConnection;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacementTransform;
import com.ionsignal.minecraft.iongenesis.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.iongenesis.model.geometry.AABB;
import com.ionsignal.minecraft.iongenesis.model.structure.JigsawData;
import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure;
import com.ionsignal.minecraft.iongenesis.util.JigsawUtils;
import com.ionsignal.minecraft.iongenesis.util.ResourceResolver;
import com.ionsignal.minecraft.iongenesis.util.SpatialMath;
import com.ionsignal.minecraft.iongenesis.util.TransformUtil;

import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.structure.Structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Logic component responsible for the geometric fitting of pieces.
 * Handles rotation, alignment, and collision detection.
 */
public class ConnectionFitter {
    private final ConfigPack pack;
    private final RandomGenerator random;
    private final Set<AABB> occupiedSpace;

    public ConnectionFitter(ConfigPack pack, RandomGenerator random, Set<AABB> occupiedSpace) {
        this.pack = pack;
        this.random = random;
        this.occupiedSpace = occupiedSpace;
    }

    /**
     * Attempts to fit one of the candidate structures onto the pending connection.
     * 
     * @param pending
     *            The open connection on the existing structure.
     * @param candidates
     *            List of structure IDs to try.
     * @param poolId
     *            The ID of the pool these candidates belong to (for tracking).
     * @return The placed piece if successful, or null.
     */
    public PlacedJigsawPiece tryFit(PendingJigsawConnection pending, List<String> candidates, String poolId) {
        // Shuffle candidates to ensure variety
        JigsawUtils.shuffle(candidates, random);
        for (String structureId : candidates) {
            NBTStructure.StructureData structureData = loadStructureData(structureId);
            if (structureData == null)
                continue;
            // Shuffle the internal jigsaw blocks of the candidate
            List<JigsawData.JigsawBlock> shuffledJigsaws = new ArrayList<>(structureData.jigsawBlocks());
            JigsawUtils.shuffle(shuffledJigsaws, random);
            for (JigsawData.JigsawBlock childJigsaw : shuffledJigsaws) {
                // Check logical connection compatibility
                if (!JigsawConnection.canConnect(pending.connection().toJigsawBlock(), childJigsaw)) {
                    continue;
                }
                // Iterate valid rotations
                List<Rotation> rotationsToTry = getRotationsForJoint(childJigsaw.info().jointType());
                for (Rotation geometricRotation : rotationsToTry) {
                    // Calculate Geometry
                    // We must manually rotate the jigsaw block descriptor to match this trial rotation
                    // before calculating alignment.
                    JigsawData.JigsawBlock rotatedChildJigsaw = rotateJigsawBlock(childJigsaw, geometricRotation, structureData.size());
                    // Calculate the dimensions of the structure AFTER the geometric rotation.
                    // This ensures the alignment calculation uses the correct bounds for the second rotation.
                    Vector3Int rotatedSize = SpatialMath.rotateDimensions(structureData.size(), geometricRotation);
                    // Calculate the alignment transform (Position + Alignment Rotation)
                    PlacementTransform alignTransform = TransformUtil.calculateAlignment(
                            pending.connection(),
                            rotatedChildJigsaw,
                            rotatedSize);
                    // The final rotation is the Geometric Rotation (Trial) + Alignment Rotation (Required)
                    Rotation finalRotation = combineRotations(geometricRotation, alignTransform.rotation());
                    Vector3Int finalPosition = alignTransform.position();
                    // Check Collision
                    AABB childBounds = AABB.fromPiece(finalPosition, structureData.size(), finalRotation);
                    if (!collides(childBounds)) {
                        List<TransformedJigsawBlock> connections = transformJigsawBlocks(
                                structureData.jigsawBlocks(), finalPosition, finalRotation, structureData.size());
                        return new PlacedJigsawPiece(
                                structureId,
                                finalPosition,
                                finalRotation,
                                structureData,
                                connections,
                                pending.depth() + 1,
                                pending.sourcePiece(),
                                poolId);
                    }
                }
            }
        }
        return null;
    }

    private NBTStructure.StructureData loadStructureData(String id) {
        Optional<Structure> structureOpt = ResourceResolver.resolve(pack.getRegistry(Structure.class), id, pack.getRegistryKey().getID());
        if (structureOpt.isPresent() && structureOpt.get() instanceof JigsawProvider provider) {
            return provider.getStructureData();
        }
        return null;
    }

    private boolean collides(AABB bounds) {
        for (AABB occupied : occupiedSpace) {
            if (bounds.intersects(occupied))
                return true;
        }
        return false;
    }

    private List<Rotation> getRotationsForJoint(JigsawData.JointType type) {
        return type == JigsawData.JointType.ROLLABLE ? List.of(Rotation.values()) : List.of(Rotation.NONE);
    }

    private JigsawData.JigsawBlock rotateJigsawBlock(JigsawData.JigsawBlock jigsaw, Rotation rotation, Vector3Int size) {
        if (rotation == Rotation.NONE)
            return jigsaw;
        Vector3Int rotatedPos = SpatialMath.rotate(jigsaw.position(), rotation, size);
        String rotatedOrientation = TransformUtil.rotateOrientation(jigsaw.orientation(), rotation);
        return new JigsawData.JigsawBlock(rotatedPos, rotatedOrientation, jigsaw.info());
    }

    private List<TransformedJigsawBlock> transformJigsawBlocks(List<JigsawData.JigsawBlock> blocks, Vector3Int pos, Rotation rot,
            Vector3Int size) {
        return blocks.stream().map(j -> TransformUtil.transformJigsawConnection(j, pos, rot, size)).collect(Collectors.toList());
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
}