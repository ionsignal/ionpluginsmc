package com.ionsignal.minecraft.iongenesis.generation;

import com.ionsignal.minecraft.iongenesis.adapter.BlockStateRotator;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.iongenesis.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.iongenesis.generation.tracking.ConnectionStatus;
import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure;
import com.ionsignal.minecraft.iongenesis.util.PaletteParser;
import com.ionsignal.minecraft.iongenesis.util.SpatialMath;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.world.WritableWorld;
import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the physical construction of a StructureBlueprint in the world.
 */
public class TerraWorldGenBuilder {
    private static final Logger LOGGER = Logger.getLogger(TerraWorldGenBuilder.class.getName());
    private final StructureBlueprint blueprint;
    private final String sealerMaterial;

    public TerraWorldGenBuilder(StructureBlueprint blueprint, String sealerMaterial) {
        this.blueprint = blueprint;
        this.sealerMaterial = sealerMaterial;
    }

    public void build(WritableWorld world, Platform platform) {
        if (blueprint.pieces().isEmpty())
            return;
        ConnectionRegistry registry = blueprint.connectionRegistry();
        int placedBlocks = 0;
        for (PlacedJigsawPiece piece : blueprint.pieces()) {
            placedBlocks += placePiece(piece, world, platform, registry, sealerMaterial);
        }
        LOGGER.fine("Builder placed " + placedBlocks + " blocks for structure " + blueprint.structureId());
    }

    /**
     * Static method to place a single piece.
     * Can be used by JigsawNBTStructure for single-piece generation.
     * 
     * @param piece
     *            The piece to place.
     * @param world
     *            The world to place it in.
     * @param platform
     *            The Terra platform.
     * @param registry
     *            Optional connection registry.
     *            If null, connection final states (air) won't be processed.
     * @return Number of blocks placed.
     */
    public static int placePiece(PlacedJigsawPiece piece, WritableWorld world, Platform platform, ConnectionRegistry registry) {
        return placePiece(piece, world, platform, registry, "minecraft:cobblestone");
    }

    /**
     * Extended placePiece method handling Sealer Material.
     */
    public static int placePiece(
            PlacedJigsawPiece piece,
            WritableWorld world,
            Platform platform,
            ConnectionRegistry registry,
            String sealerMaterial) {
        int blocksPlaced = 0;
        NBTStructure.StructureData structureData = piece.structureData();
        // Place Physical Blocks
        for (NBTStructure.BlockEntry block : structureData.blocks()) {
            try {
                NBTStructure.PaletteEntry paletteEntry = structureData.palette().get(block.state());
                BlockState terraBlockState = PaletteParser.parsePaletteEntry(paletteEntry, platform);
                if (terraBlockState == null || terraBlockState.isAir())
                    continue;
                BlockState rotatedBlockState = BlockStateRotator.rotate(terraBlockState, piece.rotation(), platform);
                Vector3Int rotatedPos = SpatialMath.rotate(block.pos(), piece.rotation(), structureData.size());
                Vector3Int finalPos = Vector3Int.of(
                        piece.worldPosition().getX() + rotatedPos.getX(),
                        piece.worldPosition().getY() + rotatedPos.getY(),
                        piece.worldPosition().getZ() + rotatedPos.getZ());
                try {
                    world.setBlockState(finalPos.getX(), finalPos.getY(), finalPos.getZ(), rotatedBlockState);
                    blocksPlaced++;
                } catch (Exception e) {
                    // Block outside writable region
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to place block in piece " + piece.structureId(), e);
            }
        }
        // Place "Final State" or "Sealer" for Connections (only if registry provided)
        if (registry != null) {
            for (TransformedJigsawBlock connection : piece.connections()) {
                // Switch on ConnectionStatus
                ConnectionStatus status = registry.getStatus(connection.position());
                if (status == ConnectionStatus.OPEN)
                    continue;
                try {
                    String blockToPlaceStr = null;
                    if (status == ConnectionStatus.CONSUMED) {
                        blockToPlaceStr = connection.info().finalState();
                    } else if (status == ConnectionStatus.SEALED) {
                        blockToPlaceStr = sealerMaterial;
                        // Note: Foundation logic for floating seals will be added in Phase 6
                    }
                    if (blockToPlaceStr != null) {
                        BlockState blockToPlace = platform.getWorldHandle().createBlockState(blockToPlaceStr);
                        if (blockToPlace != null) {
                            BlockState rotatedState = BlockStateRotator.rotate(blockToPlace, piece.rotation(), platform);
                            Vector3Int finalPos = connection.position();
                            world.setBlockState(finalPos.getX(), finalPos.getY(), finalPos.getZ(), rotatedState);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to place connection state (" + status + ")", e);
                }
            }
        }
        return blocksPlaced;
    }
}