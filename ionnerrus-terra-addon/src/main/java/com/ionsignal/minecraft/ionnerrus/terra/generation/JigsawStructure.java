package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.JigsawPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.util.ResourceResolver;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.world.WritableWorld;
import com.dfsek.terra.api.world.chunk.generation.ProtoWorld;

import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public class JigsawStructure implements Structure {
    private final JigsawStructureTemplate config;
    private final ConfigPack pack;
    private final Platform platform;
    private static final Logger LOGGER = Logger.getLogger(JigsawStructure.class.getName());

    public JigsawStructure(JigsawStructureTemplate config, ConfigPack pack, Platform platform) {
        this.config = config;
        this.pack = pack;
        this.platform = platform;
    }

    @Override
    public boolean generate(Vector3Int location, WritableWorld world, RandomGenerator random, Rotation terraRotation) {
        try {
            boolean isWorldgenContext = (world instanceof ProtoWorld);
            JigsawPlacement placement;
            // Plan / Retrieve Blueprint
            if (isWorldgenContext) {
                PlacementCacheKey cacheKey = PlacementCacheKey.from(config.getID(), pack, location, world.getSeed());
                placement = JigsawPlacementCache.getInstance().getOrGenerate(cacheKey,
                        () -> generateFullPlacement(location, cacheKey.getStructureSeed(), random));
                LOGGER.fine(String.format("[WORLDGEN] Using cached placement for '%s'", config.getID()));
            } else {
                long structureSeed = location.getX() ^ ((long) location.getZ() << 32) ^ world.getSeed();
                RandomGenerator manualRandom = RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(structureSeed);
                placement = generateFullPlacement(location, structureSeed, manualRandom);
                LOGGER.fine(String.format("[MANUAL] Generated fresh placement for '%s'", config.getID()));
            }
            if (placement == null || placement.isEmpty()) {
                return false;
            }
            // Build (Physical Placement)
            // We use the new Builder class here.
            // Note: We currently build the whole structure if any part intersects the chunk.
            // Optimization: The Builder could filter pieces based on chunk bounds.
            // Check intersection (Optimization to avoid instantiating builder if not needed)
            int centerChunkX = location.getX() >> 4;
            int centerChunkZ = location.getZ() >> 4;
            if (placement.getPiecesInChunk(centerChunkX, centerChunkZ).findAny().isEmpty()) {
                // Check adjacent chunks if structure is large?
                // Current logic in original code checked radius 1.
                // Let's trust the Builder to handle logic or keep simple check here.
                boolean intersectsRegion = false;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (placement.totalBounds().intersectsChunkRegion(centerChunkX + dx, centerChunkZ + dz)) {
                            intersectsRegion = true;
                            break;
                        }
                    }
                    if (intersectsRegion)
                        break;
                }
                if (!intersectsRegion)
                    return false;
            }
            TerraWorldGenBuilder builder = new TerraWorldGenBuilder(placement.blueprint());
            builder.build(world, platform);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error generating jigsaw structure " + config.getID(), e);
            return false;
        }
    }

    private JigsawPlacement generateFullPlacement(Vector3Int origin, long structureSeed, RandomGenerator random) {
        try {
            // Simple Placement (Single Piece)
            if (config.getStartPool() == null || config.getStartPool().isEmpty() || "minecraft:empty".equals(config.getStartPool())) {
                return generateSimplePlacement(origin, structureSeed);
            }
            // Complex Placement (Jigsaw Planner)
            StructurePlanner planner = new StructurePlanner(pack, config, origin, random, structureSeed, null);
            StructureBlueprint blueprint = planner.generateFull(config.getStartPool());
            return new JigsawPlacement(blueprint);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to generate jigsaw placement", e);
            return JigsawPlacement.empty(origin, config.getID(), new ConnectionRegistry());
        }
    }

    private JigsawPlacement generateSimplePlacement(Vector3Int origin, long structureSeed) {
        // Logic mostly unchanged, adapted to return JigsawPlacement wrapping Blueprint
        if (config.getStructure() == null || config.getStructure().isEmpty()) {
            return JigsawPlacement.empty(origin, config.getID(), new ConnectionRegistry());
        }
        Optional<Structure> structureOpt = ResourceResolver.resolve(pack.getRegistry(Structure.class), config.getStructure(),
                pack.getRegistryKey().getID());
        NBTStructure.StructureData structureData = null;
        if (structureOpt.isPresent() && structureOpt.get() instanceof JigsawProvider provider) {
            structureData = provider.getStructureData();
        }
        if (structureData == null)
            return JigsawPlacement.empty(origin, config.getID(), new ConnectionRegistry());
        RandomGenerator random = RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(structureSeed);
        Rotation rotation = Rotation.values()[random.nextInt(Rotation.values().length)];
        PlacedJigsawPiece singlePiece = PlacedJigsawPiece.createStartPiece(config.getStructure(), origin, rotation, structureData,
                Collections.emptyList(), null);
        StructureBlueprint blueprint = new StructureBlueprint(config.getID(), origin, List.of(singlePiece), new ConnectionRegistry(), null,
                null, null);
        return new JigsawPlacement(blueprint);
    }
}