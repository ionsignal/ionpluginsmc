package com.ionsignal.minecraft.iongenesis.generation.api;

import com.ionsignal.minecraft.iongenesis.generation.builder.TerraWorldGenBuilder;
import com.ionsignal.minecraft.iongenesis.generation.components.JigsawProvider;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.iongenesis.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure;
import com.ionsignal.minecraft.iongenesis.util.TransformUtil;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.registry.key.Keyed;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.world.WritableWorld;

import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.List;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

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
        // Calculate connections to enable post-placement cleanup (replacing jigsaw blocks)
        List<TransformedJigsawBlock> connections = data.jigsawBlocks().stream()
                .map(j -> TransformUtil.transformJigsawConnection(j, location, rotation, data.size()))
                .collect(Collectors.toList());
        // Create a temporary PlacedJigsawPiece to represent this single generation attempt
        PlacedJigsawPiece piece = PlacedJigsawPiece.createStartPiece(
                id.toString(),
                location,
                rotation,
                data,
                connections, // Pass calculated connections instead of empty list
                null);
        // Create a temporary registry to handle jigsaw block cleanup
        ConnectionRegistry registry = new ConnectionRegistry();
        for (TransformedJigsawBlock connection : connections) {
            registry.markConsumed(connection.position()); // Mark as consumed to trigger final_state replacement
        }
        // Delegate to the shared builder logic with the registry
        TerraWorldGenBuilder.placePiece(piece, world, platform, registry);
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