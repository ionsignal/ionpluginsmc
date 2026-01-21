package com.ionsignal.minecraft.iongenesis.generation.api;

import com.ionsignal.minecraft.iongenesis.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.iongenesis.generation.JigsawPlacementCache;
import com.ionsignal.minecraft.iongenesis.generation.StructureBlueprint;
import com.ionsignal.minecraft.iongenesis.generation.StructurePlanner;
import com.ionsignal.minecraft.iongenesis.generation.builder.TerraWorldGenBuilder;
import com.ionsignal.minecraft.iongenesis.generation.components.JigsawProvider;
import com.ionsignal.minecraft.iongenesis.generation.engine.PlacementCacheKey;
import com.ionsignal.minecraft.iongenesis.generation.oracle.TerraGeneratorOracle;
import com.ionsignal.minecraft.iongenesis.generation.oracle.TerrainOracle;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure;
import com.ionsignal.minecraft.iongenesis.util.ResourceResolver;
import com.ionsignal.minecraft.iongenesis.util.SystemContext;

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
        try (SystemContext ignored = new SystemContext()) {
            boolean isWorldgenContext = (world instanceof ProtoWorld);
            StructureBlueprint blueprint;
            // Plan / Retrieve Blueprint
            if (isWorldgenContext) {
                PlacementCacheKey cacheKey = PlacementCacheKey.from(config.getID(), pack, location, world.getSeed());
                TerrainOracle oracle = new TerraGeneratorOracle(world.getGenerator(), world, world.getBiomeProvider());
                blueprint = JigsawPlacementCache.getInstance().getOrGenerate(
                        cacheKey, () -> generateFullBlueprint(
                                location,
                                cacheKey.getStructureSeed(),
                                random,
                                oracle));
                LOGGER.fine(String.format("[WORLDGEN] Using cached placement for '%s'", config.getID()));
            } else {
                long structureSeed = location.getX() ^ ((long) location.getZ() << 32) ^ world.getSeed();
                RandomGenerator manualRandom = RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(structureSeed);
                TerrainOracle oracle = new TerraGeneratorOracle(world.getGenerator(), world, world.getBiomeProvider());
                blueprint = generateFullBlueprint(location, structureSeed, manualRandom, oracle); // Renamed method call
                LOGGER.fine(String.format("[MANUAL] Generated fresh placement for '%s'", config.getID()));
            }
            if (blueprint == null || blueprint.isEmpty()) {
                return false;
            }
            // Build (Physical Placement)
            // We use the new Builder class here.
            // Note: We currently build the whole structure if any part intersects the chunk.
            // Optimization: The Builder could filter pieces based on chunk bounds.
            // Check intersection (Optimization to avoid instantiating builder if not needed)
            int centerChunkX = location.getX() >> 4;
            int centerChunkZ = location.getZ() >> 4;
            if (blueprint.getPiecesInChunk(centerChunkX, centerChunkZ).findAny().isEmpty()) {
                // Check adjacent chunks if structure is large?
                // Current logic in original code checked radius 1.
                // Let's trust the Builder to handle logic or keep simple check here.
                boolean intersectsRegion = false;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (blueprint.totalBounds().intersectsChunkRegion(centerChunkX + dx, centerChunkZ + dz)) {
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
            // Pass the sealer material from config to the builder
            // Passed blueprint directly, removed .blueprint() accessor
            TerraWorldGenBuilder builder = new TerraWorldGenBuilder(blueprint, config.getSealerMaterial());
            builder.build(world, platform);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error generating jigsaw structure " + config.getID(), e);
            return false;
        }
    }

    private StructureBlueprint generateFullBlueprint(Vector3Int origin, long structureSeed, RandomGenerator random, TerrainOracle oracle) {
        try {
            // Simple Placement (Single Piece)
            if (config.getStartPool() == null || config.getStartPool().isEmpty() || "minecraft:empty".equals(config.getStartPool())) {
                return generateSimpleBlueprint(origin, structureSeed);
            }
            // Complex Placement (Jigsaw Planner)
            StructurePlanner planner = new StructurePlanner(pack, config, origin, random, structureSeed, null, null, oracle);
            return planner.generateFull(config.getStartPool()); // Return blueprint directly
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to generate jigsaw placement", e);
            return StructureBlueprint.empty(origin, config.getID(), new ConnectionRegistry());
        }
    }

    private StructureBlueprint generateSimpleBlueprint(Vector3Int origin, long structureSeed) {
        // Logic adapted to return StructureBlueprint directly
        if (config.getStructure() == null || config.getStructure().isEmpty()) {
            return StructureBlueprint.empty(origin, config.getID(), new ConnectionRegistry());
        }
        Optional<Structure> structureOpt = ResourceResolver.resolve(pack.getRegistry(Structure.class), config.getStructure(),
                pack.getRegistryKey().getID());
        NBTStructure.StructureData structureData = null;
        if (structureOpt.isPresent() && structureOpt.get() instanceof JigsawProvider provider) {
            structureData = provider.getStructureData();
        }
        if (structureData == null)
            return StructureBlueprint.empty(origin, config.getID(), new ConnectionRegistry());
        RandomGenerator random = RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(structureSeed);
        Rotation rotation = Rotation.values()[random.nextInt(Rotation.values().length)];
        PlacedJigsawPiece singlePiece = PlacedJigsawPiece.createStartPiece(config.getStructure(), origin, rotation, structureData,
                Collections.emptyList(), null);
        return new StructureBlueprint(
                config.getID(),
                origin,
                List.of(singlePiece),
                new ConnectionRegistry(),
                null, null, null);
    }

    public JigsawStructureTemplate getConfig() {
        return config;
    }
}