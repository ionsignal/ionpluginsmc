package com.ionsignal.minecraft.iongenesis.generation;

import com.ionsignal.minecraft.iongenesis.model.NBTStructure;
import com.ionsignal.minecraft.iongenesis.model.NBTStructure.BlockEntry;
import com.ionsignal.minecraft.iongenesis.model.NBTStructure.PaletteEntry;
import com.ionsignal.minecraft.iongenesis.model.NBTStructure.StructureData;
import com.ionsignal.minecraft.iongenesis.util.BlockStateRotator;
import com.ionsignal.minecraft.iongenesis.util.CoordinateConverter;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.registry.key.Keyed;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.world.WritableWorld;

import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.random.RandomGenerator;

/**
 * Adapter class that wraps parsed NBT structure data into a Terra Structure. Implements
 * JigsawProvider to expose data for the generator.
 */
public class JigsawNBTStructure implements Structure, Keyed<JigsawNBTStructure>, JigsawProvider {
    private final RegistryKey id;
    private final NBTStructure.StructureData data; // Explicit reference to model
    private final Platform platform;

    public JigsawNBTStructure(RegistryKey id, StructureData data, Platform platform) {
        this.id = id;
        this.data = data;
        this.platform = platform;
    }

    @Override
    public boolean generate(Vector3Int location, WritableWorld world, RandomGenerator random, Rotation rotation) {
        // Basic generation logic for placing this single piece
        // Used if this structure is placed directly via /terra structure place
        for (BlockEntry block : data.blocks()) {
            try {
                PaletteEntry paletteEntry = data.palette().get(block.state());
                BlockState terraBlockState = CoordinateConverter.parsePaletteEntry(paletteEntry, platform);
                if (terraBlockState == null || terraBlockState.isAir()) {
                    continue;
                }
                BlockState rotatedBlockState = BlockStateRotator.rotate(terraBlockState, rotation, platform);
                Vector3Int rotatedPos = CoordinateConverter.rotate(block.pos(), rotation, data.size());
                // Calculate world position
                int x = location.getX() + rotatedPos.getX();
                int y = location.getY() + rotatedPos.getY();
                int z = location.getZ() + rotatedPos.getZ();
                world.setBlockState(x, y, z, rotatedBlockState);
            } catch (Exception e) {
                // Skip invalid blocks
            }
        }
        return true;
    }

    @Override
    public RegistryKey getRegistryKey() {
        return id;
    }

    @Override
    public StructureData getStructureData() {
        return data;
    }
}