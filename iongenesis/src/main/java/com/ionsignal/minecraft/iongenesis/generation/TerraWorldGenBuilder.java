package com.ionsignal.minecraft.iongenesis.generation;

import com.ionsignal.minecraft.iongenesis.adapter.BlockStateRotator;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.iongenesis.generation.tracking.ConnectionRegistry;
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

    public TerraWorldGenBuilder(StructureBlueprint blueprint) {
        this.blueprint = blueprint;
    }

    public void build(WritableWorld world, Platform platform) {
        if (blueprint.pieces().isEmpty())
            return;
        ConnectionRegistry registry = blueprint.connectionRegistry();
        int placedBlocks = 0;
        for (PlacedJigsawPiece piece : blueprint.pieces()) {
            placedBlocks += placePiece(piece, world, platform, registry);
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
        // Place "Final State" for Consumed Connections (only if registry provided)
        if (registry != null) {
            for (TransformedJigsawBlock connection : piece.connections()) {
                if (registry.isConsumed(connection.position())) {
                    try {
                        String finalStateStr = connection.info().finalState();
                        BlockState finalBlockState = platform.getWorldHandle().createBlockState(finalStateStr);
                        if (finalBlockState == null)
                            continue;
                        BlockState rotatedFinalState = BlockStateRotator.rotate(finalBlockState, piece.rotation(), platform);
                        Vector3Int finalPos = connection.position();
                        world.setBlockState(finalPos.getX(), finalPos.getY(), finalPos.getZ(), rotatedFinalState);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to place final_state for connection", e);
                    }
                }
            }
        }
        return blocksPlaced;
    }
}