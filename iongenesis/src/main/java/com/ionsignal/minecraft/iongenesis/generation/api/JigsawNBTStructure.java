package com.ionsignal.minecraft.iongenesis.generation.api;

import com.ionsignal.minecraft.iongenesis.generation.builder.TerraWorldGenBuilder;
import com.ionsignal.minecraft.iongenesis.generation.components.JigsawProvider;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.registry.key.Keyed;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.world.WritableWorld;

import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.Collections;
import java.util.random.RandomGenerator;

/**
 * Adapter class that wraps parsed NBT structure data into a Terra Structure.
 */
public class JigsawNBTStructure implements Structure, Keyed<JigsawNBTStructure>, JigsawProvider {
    private final RegistryKey id;
    private final NBTStructure.StructureData data;
    private final Platform platform;

    public JigsawNBTStructure(RegistryKey id, NBTStructure.StructureData data, Platform platform) {
        this.id = id;
        this.data = data;
        this.platform = platform;
    }

    @Override
    public boolean generate(Vector3Int location, WritableWorld world, RandomGenerator random, Rotation rotation) {
        // Create a temporary PlacedJigsawPiece to represent this single generation attempt
        PlacedJigsawPiece piece = PlacedJigsawPiece.createStartPiece(
                id.toString(),
                location,
                rotation,
                data,
                Collections.emptyList(), // No connections needed for single placement
                null);
        // Delegate to the shared builder logic
        // We pass null for registry because single-piece placement doesn't process connections
        TerraWorldGenBuilder.placePiece(piece, world, platform, null);
        return true;
    }

    @Override
    public RegistryKey getRegistryKey() {
        return id;
    }

    @Override
    public NBTStructure.StructureData getStructureData() {
        return data;
    }
}