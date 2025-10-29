package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.util.Rotation;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.world.WritableWorld;
import com.dfsek.terra.api.world.chunk.generation.ProtoWorld;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.JigsawPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.ionnerrus.terra.util.BlockStateRotator;
import com.ionsignal.minecraft.ionnerrus.terra.util.CoordinateConverter;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.provider.NBTStructureProvider;

import java.util.Random;
import java.util.logging.Logger;
import java.util.logging.Level;

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
	public boolean generate(Vector3Int location, WritableWorld world, Random random, Rotation terraRotation) {
		try {
			boolean isWorldgenContext = (world instanceof ProtoWorld);
			JigsawPlacement placement;
			if (isWorldgenContext) {
				// This prevents the same structure from being generated multiple times in overlapping chunks
				PlacementCacheKey cacheKey = PlacementCacheKey.from(
						config.getID(),
						pack,
						location,
						world.getSeed());
				placement = JigsawPlacementCache.getInstance().getOrGenerate(cacheKey,
						() -> generateFullPlacement(location, cacheKey.getStructureSeed()));
				LOGGER.fine(String.format(
						"[WORLDGEN] Using cached placement for structure '%s' at chunk (%d, %d)",
						config.getID(), cacheKey.spawnChunkX(), cacheKey.spawnChunkZ()));
			} else {
				// This fixes the bug where multiple commands in the same chunk would reuse the cached location
				// We still use the world seed to keep the structure composition deterministic
				long structureSeed = location.getX() ^ ((long) location.getZ() << 32) ^ world.getSeed();
				placement = generateFullPlacement(location, structureSeed);
				LOGGER.fine(String.format(
						"[MANUAL] Generated fresh placement for structure '%s' at exact location %s",
						config.getID(), location));
			}
			// Check if we have a valid placement
			if (placement == null || placement.isEmpty()) {
				LOGGER.warning(String.format(
						"No valid jigsaw placement generated for structure '%s' at %s",
						config.getID(), location));
				return false;
			}
			// Determine the bounds of the current ProtoWorld We need to be defensive here since we don't know
			// the exact buffer size so ssume a reasonable buffer (3x3 chunks is common for Terra) This is
			// defensive - we'll check bounds when placing blocks
			int centerChunkX = location.getX() >> 4;
			int centerChunkZ = location.getZ() >> 4;
			int chunkRadius = 1;
			// Step 5: Place all pieces that intersect with the current chunk region
			int placedPieces = 0;
			int placedBlocks = 0;
			for (PlacedJigsawPiece piece : placement.pieces()) {
				// Check if this piece intersects the current chunk region
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
					continue; // Skip pieces that don't intersect our region
				}
				// Place this piece's blocks
				int blocksPlaced = placePieceBlocks(piece, world);
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

	/**
	 * Generates the complete jigsaw placement for a structure.
	 * This is only called once per unique structure location and cached.
	 */
	private JigsawPlacement generateFullPlacement(Vector3Int origin, long structureSeed) {
		try {
			// Check if we're using jigsaw generation or simple file placement
			if (config.getStartPool() == null ||
					config.getStartPool().isEmpty() ||
					"minecraft:empty".equals(config.getStartPool())) {
				// Fallback to simple single-file placement for backwards compatibility
				return generateSimplePlacement(origin, structureSeed);
			}
			// Full jigsaw generation
			LOGGER.info(String.format(
					"Generating jigsaw structure '%s' at %s with start pool '%s'",
					config.getID(), origin, config.getStartPool()));
			JigsawGenerator generator = new JigsawGenerator(
					pack,
					platform,
					config,
					origin,
					structureSeed);
			JigsawPlacement placement = generator.generate(config.getStartPool());
			// Log generation summary
			LOGGER.info(placement.getSummary());
			return placement;
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, String.format(
					"Failed to generate jigsaw placement for structure '%s'",
					config.getID()), e);
			return JigsawPlacement.empty(origin, config.getID());
		}
	}

	/**
	 * Generates a simple single-piece placement for non-jigsaw structures.
	 * This maintains backwards compatibility with the original implementation.
	 */
	private JigsawPlacement generateSimplePlacement(Vector3Int origin, long structureSeed) {
		if (config.getFile() == null || config.getFile().isEmpty()) {
			return JigsawPlacement.empty(origin, config.getID());
		}
		NBTStructure.StructureData structureData = NBTStructureProvider.getInstance()
				.load(pack, config.getFile());
		if (structureData == null) {
			LOGGER.warning(String.format(
					"Failed to load NBT data for structure '%s' from file '%s'",
					config.getID(), config.getFile()));
			return JigsawPlacement.empty(origin, config.getID());
		}
		// Create a single-piece placement with random rotation
		Random random = new Random(structureSeed);
		Rotation[] rotations = Rotation.values();
		Rotation rotation = rotations[random.nextInt(rotations.length)];
		// Include sourcePoolId as null for simple placement
		PlacedJigsawPiece singlePiece = PlacedJigsawPiece.createStartPiece(
				config.getFile(),
				origin,
				rotation,
				structureData,
				java.util.Collections.emptyList(), // No connections for simple placement
				null); // sourcePoolId is null for non-jigsaw structures
		return new JigsawPlacement(
				java.util.List.of(singlePiece),
				origin,
				config.getID());
	}

	/**
	 * Places the blocks from a single jigsaw piece into the world.
	 * 
	 * @param piece
	 *            The piece to place
	 * @param world
	 *            The world to place blocks in
	 * @return The number of blocks successfully placed
	 */
	private int placePieceBlocks(PlacedJigsawPiece piece, WritableWorld world) {
		int blocksPlaced = 0;
		NBTStructure.StructureData structureData = piece.structureData();
		for (NBTStructure.BlockEntry block : structureData.blocks()) {
			try {
				// Get the block state from the palette
				NBTStructure.PaletteEntry paletteEntry = structureData.palette().get(block.state());
				BlockState terraBlockState = CoordinateConverter.parsePaletteEntry(paletteEntry, platform);
				if (terraBlockState == null || terraBlockState.isAir()) {
					continue; // Skip air blocks
				}
				// Updated to use new BlockStateRotator with platform parameter
				BlockState rotatedBlockState = BlockStateRotator.rotate(terraBlockState, piece.rotation(), platform);
				// Calculate the world position for this block
				Vector3Int rotatedPos = CoordinateConverter.rotate(
						block.pos(),
						piece.rotation(),
						structureData.size());
				Vector3Int finalPos = Vector3Int.of(
						piece.worldPosition().getX() + rotatedPos.getX(),
						piece.worldPosition().getY() + rotatedPos.getY(),
						piece.worldPosition().getZ() + rotatedPos.getZ());
				// Enhanced defensive bounds checking with try-catch
				try {
					world.setBlockState(
							finalPos.getX(),
							finalPos.getY(),
							finalPos.getZ(),
							rotatedBlockState);
					blocksPlaced++;
				} catch (Exception e) {
					// Silent catch - Block is outside the writable region
					// This is expected for pieces at the edge of the ProtoWorld
					// Optionally log at FINEST level for debugging
					// LOGGER.finest("Block at " + finalPos + " is outside writable region");
				}
			} catch (Exception e) {
				LOGGER.warning(String.format(
						"Failed to place block at index %d for piece %s: %s",
						block.state(), piece.nbtFile(), e.getMessage()));
			}
		}
		for (TransformedJigsawBlock connection : piece.connections()) {
			if (connection.isConsumed()) {
				try {
					String finalStateStr = connection.info().finalState();
					BlockState finalBlockState = platform.getWorldHandle().createBlockState(finalStateStr);
					if (finalBlockState == null) {
						LOGGER.warning("Failed to parse final_state: " + finalStateStr);
						continue;
					}
					BlockState rotatedFinalState = BlockStateRotator.rotate(finalBlockState, piece.rotation(), platform);
					Vector3Int finalPos = connection.position(); // Position is already in world space
					world.setBlockState(finalPos.getX(), finalPos.getY(), finalPos.getZ(), rotatedFinalState);
				} catch (Exception e) {
					// This can fail if the block is outside the writable region or if the state string is invalid.
					LOGGER.log(Level.WARNING, "Failed to place final_state for connection at " + connection.position(), e);
				}
			}
		}
		return blocksPlaced;
	}
}