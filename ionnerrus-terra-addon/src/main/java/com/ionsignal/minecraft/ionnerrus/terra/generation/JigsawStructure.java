package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.JigsawPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.util.BlockStateRotator;
import com.ionsignal.minecraft.ionnerrus.terra.util.CoordinateConverter;
import com.ionsignal.minecraft.ionnerrus.terra.util.ResourceResolver;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.world.WritableWorld;
import com.dfsek.terra.api.world.chunk.generation.ProtoWorld;

import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

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
            if (isWorldgenContext) {
                PlacementCacheKey cacheKey = PlacementCacheKey.from(
                        config.getID(),
                        pack,
                        location,
                        world.getSeed());
                placement = JigsawPlacementCache.getInstance().getOrGenerate(cacheKey,
                        () -> generateFullPlacement(location, cacheKey.getStructureSeed(), random));
                LOGGER.fine(String.format(
                        "[WORLDGEN] Using cached placement for structure '%s' at chunk (%d, %d)",
                        config.getID(), cacheKey.spawnChunkX(), cacheKey.spawnChunkZ()));
            } else {
                long structureSeed = location.getX() ^ ((long) location.getZ() << 32) ^ world.getSeed();
                RandomGenerator manualRandom = RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(structureSeed);
                placement = generateFullPlacement(location, structureSeed, manualRandom);
                LOGGER.fine(String.format(
                        "[MANUAL] Generated fresh placement for structure '%s' at exact location %s",
                        config.getID(), location));
            }
            if (placement == null || placement.isEmpty()) {
                LOGGER.warning(String.format(
                        "No valid jigsaw placement generated for structure '%s' at %s",
                        config.getID(), location));
                return false;
            }
            int centerChunkX = location.getX() >> 4;
            int centerChunkZ = location.getZ() >> 4;
            int chunkRadius = 1;
            int placedPieces = 0;
            int placedBlocks = 0;
            ConnectionRegistry registry = placement.connectionRegistry();
            for (PlacedJigsawPiece piece : placement.pieces()) {
                boolean intersectsRegion = false;
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                        if (piece.intersectsChunk(centerChunkX + dx, centerChunkZ + dz)) {
                            intersectsRegion = true;
                            break;
                        }
                    }
                    if (intersectsRegion)
                        break;
                }
                if (!intersectsRegion) {
                    continue;
                }
                int blocksPlaced = placePieceBlocks(piece, world, registry);
                if (blocksPlaced > 0) {
                    placedPieces++;
                    placedBlocks += blocksPlaced;
                }
            }
            if (placedPieces > 0) {
                LOGGER.info(String.format(
                        "Placed %d pieces (%d blocks) from structure '%s' in chunk region around (%d, %d)",
                        placedPieces, placedBlocks, config.getID(), centerChunkX, centerChunkZ));
            }
            return placedPieces > 0;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, String.format(
                    "Error generating jigsaw structure '%s' at %s",
                    config.getID(), location), e);
            return false;
        }
    }

    private JigsawPlacement generateFullPlacement(Vector3Int origin, long structureSeed, RandomGenerator random) {
        try {
            if (config.getStartPool() == null || config.getStartPool().isEmpty() || "minecraft:empty".equals(config.getStartPool())) {
                return generateSimplePlacement(origin, structureSeed);
            }
            LOGGER.info(String.format(
                    "Generating jigsaw structure '%s' at %s with start pool '%s'",
                    config.getID(), origin, config.getStartPool()));
            JigsawGenerator generator = new JigsawGenerator(
                    pack,
                    platform,
                    config,
                    origin,
                    random,
                    structureSeed);
            JigsawPlacement placement = generator.generate(config.getStartPool());
            LOGGER.info(placement.getSummary());
            return placement;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, String.format(
                    "Failed to generate jigsaw placement for structure '%s'",
                    config.getID()), e);
            return JigsawPlacement.empty(origin, config.getID(), new ConnectionRegistry());
        }
    }

    private JigsawPlacement generateSimplePlacement(Vector3Int origin, long structureSeed) {
        // Use Registry lookup for simple placement
        if (config.getStructure() == null || config.getStructure().isEmpty()) {
            return JigsawPlacement.empty(origin, config.getID(), new ConnectionRegistry());
        }
        NBTStructure.StructureData structureData = null;
        String structureId = config.getStructure();
        // Use ResourceResolver for consistent lookup strategy
        Optional<Structure> structureOpt = ResourceResolver.resolve(
                pack.getRegistry(Structure.class), structureId,
                pack.getRegistryKey().getID());
        if (structureOpt.isPresent() && structureOpt.get() instanceof JigsawProvider provider) {
            structureData = provider.getStructureData();
        }
        if (structureData == null) {
            LOGGER.warning(String.format(
                    "Failed to load NBT data for structure '%s' from registry ID '%s'",
                    config.getID(), config.getStructure()));
            return JigsawPlacement.empty(origin, config.getID(), new ConnectionRegistry());
        }
        RandomGenerator random = RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(structureSeed);
        Rotation[] rotations = Rotation.values();
        Rotation rotation = rotations[random.nextInt(rotations.length)];
        PlacedJigsawPiece singlePiece = PlacedJigsawPiece.createStartPiece(
                config.getStructure(),
                origin,
                rotation,
                structureData,
                java.util.Collections.emptyList(),
                null);
        return new JigsawPlacement(
                java.util.List.of(singlePiece),
                origin,
                config.getID(),
                new ConnectionRegistry());
    }

    private int placePieceBlocks(PlacedJigsawPiece piece, WritableWorld world, ConnectionRegistry registry) {
        int blocksPlaced = 0;
        NBTStructure.StructureData structureData = piece.structureData();
        for (NBTStructure.BlockEntry block : structureData.blocks()) {
            try {
                NBTStructure.PaletteEntry paletteEntry = structureData.palette().get(block.state());
                BlockState terraBlockState = CoordinateConverter.parsePaletteEntry(paletteEntry, platform);
                if (terraBlockState == null || terraBlockState.isAir()) {
                    continue;
                }
                BlockState rotatedBlockState = BlockStateRotator.rotate(terraBlockState, piece.rotation(), platform);
                Vector3Int rotatedPos = CoordinateConverter.rotate(
                        block.pos(),
                        piece.rotation(),
                        structureData.size());
                Vector3Int finalPos = Vector3Int.of(
                        piece.worldPosition().getX() + rotatedPos.getX(),
                        piece.worldPosition().getY() + rotatedPos.getY(),
                        piece.worldPosition().getZ() + rotatedPos.getZ());
                try {
                    world.setBlockState(
                            finalPos.getX(),
                            finalPos.getY(),
                            finalPos.getZ(),
                            rotatedBlockState);
                    blocksPlaced++;
                } catch (Exception e) {
                    // Block is outside the writable region
                }
            } catch (Exception e) {
                LOGGER.warning(String.format(
                        "Failed to place block at index %d for piece %s: %s",
                        block.state(), piece.structureId(), e.getMessage()));
            }
        }
        for (TransformedJigsawBlock connection : piece.connections()) {
            if (registry.isConsumed(connection.position())) {
                try {
                    String finalStateStr = connection.info().finalState();
                    BlockState finalBlockState = platform.getWorldHandle().createBlockState(finalStateStr);
                    if (finalBlockState == null) {
                        LOGGER.warning("Failed to parse final_state: " + finalStateStr);
                        continue;
                    }
                    BlockState rotatedFinalState = BlockStateRotator.rotate(finalBlockState, piece.rotation(), platform);
                    Vector3Int finalPos = connection.position();
                    world.setBlockState(finalPos.getX(), finalPos.getY(), finalPos.getZ(), rotatedFinalState);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to place final_state for connection at " + connection.position(), e);
                }
            }
        }
        return blocksPlaced;
    }
}