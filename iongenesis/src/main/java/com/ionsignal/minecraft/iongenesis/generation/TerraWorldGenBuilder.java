package com.ionsignal.minecraft.iongenesis.generation;

import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.iongenesis.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.iongenesis.model.NBTStructure;
import com.ionsignal.minecraft.iongenesis.util.BlockStateRotator;
import com.ionsignal.minecraft.iongenesis.util.CoordinateConverter;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.world.WritableWorld;

import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the physical construction of a StructureBlueprint in the world.
 * Isolates NMS/BlockState logic from the planner.
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
        // Optimization:
        // Calculate chunk region to skip processing if needed?
        // For now, we iterate all pieces as per original implementation.
        ConnectionRegistry registry = blueprint.connectionRegistry();
        int placedBlocks = 0;
        for (PlacedJigsawPiece piece : blueprint.pieces()) {
            placedBlocks += placePieceBlocks(piece, world, registry, platform);
        }
        LOGGER.fine("Builder placed " + placedBlocks + " blocks for structure " + blueprint.structureId());
    }

    private int placePieceBlocks(PlacedJigsawPiece piece, WritableWorld world, ConnectionRegistry registry, Platform platform) {
        int blocksPlaced = 0;
        NBTStructure.StructureData structureData = piece.structureData();
        // Place Physical Blocks
        for (NBTStructure.BlockEntry block : structureData.blocks()) {
            try {
                NBTStructure.PaletteEntry paletteEntry = structureData.palette().get(block.state());
                BlockState terraBlockState = CoordinateConverter.parsePaletteEntry(paletteEntry, platform);
                if (terraBlockState == null || terraBlockState.isAir())
                    continue;
                BlockState rotatedBlockState = BlockStateRotator.rotate(terraBlockState, piece.rotation(), platform);
                Vector3Int rotatedPos = CoordinateConverter.rotate(block.pos(), piece.rotation(), structureData.size());
                Vector3Int finalPos = Vector3Int.of(
                        piece.worldPosition().getX() + rotatedPos.getX(),
                        piece.worldPosition().getY() + rotatedPos.getY(),
                        piece.worldPosition().getZ() + rotatedPos.getZ());
                try {
                    world.setBlockState(finalPos.getX(), finalPos.getY(), finalPos.getZ(), rotatedBlockState);
                    blocksPlaced++;
                } catch (Exception e) {
                    // Block outside writable region (Chunk generation edge case)
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to place block in piece " + piece.structureId(), e);
            }
        }
        // Place "Final State" for Consumed Connections (e.g., replace structure void with air)
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
        return blocksPlaced;
    }
}