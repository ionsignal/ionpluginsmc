package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.NerrusTerraAddon;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.generation.debug.DebugCommand;
import com.ionsignal.minecraft.ionnerrus.terra.generation.debug.DebugContext;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.JigsawPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.provider.NBTStructureProvider;
import com.ionsignal.minecraft.ionnerrus.terra.util.BlockStateRotator;
import com.ionsignal.minecraft.ionnerrus.terra.util.CoordinateConverter;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.util.Rotation;
import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.world.WritableWorld;
import com.dfsek.terra.api.world.chunk.generation.ProtoWorld;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Random;
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
	public boolean generate(Vector3Int location, WritableWorld world, Random random, Rotation terraRotation) {
		try {
			boolean isWorldgenContext = (world instanceof ProtoWorld);
			// CHANGED: The logic for finding a debug context is now robust and correct.
			// It no longer relies on a Player object, which is unavailable in this method.
			if (!isWorldgenContext) {
				// Use the generation task's own parameters to look up a matching debug session.
				DebugContext debugContext = DebugCommand.getDebugContextForTask(config.getID(), location);
				if (debugContext != null) {
					// A matching debug session was found. We will handle generation asynchronously.
					LOGGER.info("Debug session detected. Starting asynchronous generation for " + config.getID());
					runAsynchronousDebugGeneration(location, world, debugContext);
					return true; // Signal that generation is being handled.
				}
			}
			JigsawPlacement placement;
			if (isWorldgenContext) {
				PlacementCacheKey cacheKey = PlacementCacheKey.from(
						config.getID(),
						pack,
						location,
						world.getSeed());
				placement = JigsawPlacementCache.getInstance().getOrGenerate(cacheKey,
						() -> generateFullPlacement(location, cacheKey.getStructureSeed(), null));
				LOGGER.fine(String.format(
						"[WORLDGEN] Using cached placement for structure '%s' at chunk (%d, %d)",
						config.getID(), cacheKey.spawnChunkX(), cacheKey.spawnChunkZ()));
			} else {
				long structureSeed = location.getX() ^ ((long) location.getZ() << 32) ^ world.getSeed();
				placement = generateFullPlacement(location, structureSeed, null);
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

	private void runAsynchronousDebugGeneration(Vector3Int location, WritableWorld world, DebugContext debugContext) {
		Plugin plugin = NerrusTerraAddon.getTerraPlugin();
		if (plugin == null) {
			LOGGER.severe("Cannot start debug generation: Terra plugin not found!");
			DebugCommand.clearActiveSession(debugContext.getPlayerId());
			return;
		}
		new BukkitRunnable() {
			@Override
			public void run() {
				long structureSeed = location.getX() ^ ((long) location.getZ() << 32) ^ world.getSeed();
				JigsawPlacement placement = generateFullPlacement(location, structureSeed, debugContext);
				if (placement == null || placement.isEmpty()) {
					LOGGER.warning("Debug generation resulted in an empty placement.");
					return;
				}
				new BukkitRunnable() {
					@Override
					public void run() {
						LOGGER.info("Placing debug-generated structure on main thread...");
						int placedBlocks = 0;
						for (PlacedJigsawPiece piece : placement.pieces()) {
							placedBlocks += placePieceBlocks(piece, world);
						}
						LOGGER.info("Finished placing " + placedBlocks + " blocks.");
						// CHANGED: Safely send message to player if they are still online.
						debugContext.getPlayer().ifPresent(p -> p.sendMessage("Debug generation and placement complete."));
						// CHANGED: Correctly clear the session using the player's UUID.
						DebugCommand.clearActiveSession(debugContext.getPlayerId());
					}
				}.runTask(plugin);
			}
		}.runTaskAsynchronously(plugin);
	}

	private JigsawPlacement generateFullPlacement(Vector3Int origin, long structureSeed, DebugContext debugContext) {
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
					structureSeed,
					debugContext);
			JigsawPlacement placement = generator.generate(config.getStartPool());
			LOGGER.info(placement.getSummary());
			return placement;
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, String.format(
					"Failed to generate jigsaw placement for structure '%s'",
					config.getID()), e);
			return JigsawPlacement.empty(origin, config.getID());
		}
	}

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
		Random random = new Random(structureSeed);
		Rotation[] rotations = Rotation.values();
		Rotation rotation = rotations[random.nextInt(rotations.length)];
		PlacedJigsawPiece singlePiece = PlacedJigsawPiece.createStartPiece(
				config.getFile(),
				origin,
				rotation,
				structureData,
				java.util.Collections.emptyList(),
				null);
		return new JigsawPlacement(
				java.util.List.of(singlePiece),
				origin,
				config.getID());
	}

	private int placePieceBlocks(PlacedJigsawPiece piece, WritableWorld world) {
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
					Vector3Int finalPos = connection.position();
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