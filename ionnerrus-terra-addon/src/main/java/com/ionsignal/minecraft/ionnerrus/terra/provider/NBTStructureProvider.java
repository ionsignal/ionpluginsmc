package com.ionsignal.minecraft.ionnerrus.terra.provider;

import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.model.JigsawData;

import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.config.ConfigPack;

import com.ionsignal.minecraft.ionnerrus.terra.util.NBTUtil;

import de.bluecolored.bluenbt.BlueNBT;
import de.bluecolored.bluenbt.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Thread-safe provider for loading and caching NBT structure templates.
 * This service uses BlueNBT to parse .nbt files into a clean, NMS-independent data model.
 */
public class NBTStructureProvider {
	private static final NBTStructureProvider INSTANCE = new NBTStructureProvider();
	private static final BlueNBT blueNBT = new BlueNBT();
	private static final Logger LOGGER = Logger.getLogger(NBTStructureProvider.class.getName());

	private final Map<String, NBTStructure.StructureData> structureCache = new ConcurrentHashMap<>();
	private final Object loadLock = new Object();

	private NBTStructureProvider() {
		// Private constructor for singleton
	}

	public static NBTStructureProvider getInstance() {
		return INSTANCE;
	}

	/**
	 * Loads an NBT structure from the specified path, parsing it into a StructureData record.
	 * Structures are cached for reuse across multiple generation calls.
	 *
	 * @param pack
	 *            The Terra config pack containing the structure.
	 * @param filePath
	 *            The relative path to the NBT file from the pack root.
	 * @return The parsed StructureData, or null if loading failed.
	 */
	@SuppressWarnings("unchecked")
	public NBTStructure.StructureData load(ConfigPack pack, String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			LOGGER.warning("Cannot load structure: file path is null or empty.");
			return null;
		}

		String cacheKey = pack.getRegistryKey().toString() + ":" + filePath;

		NBTStructure.StructureData cached = structureCache.get(cacheKey);
		if (cached != null) {
			return cached;
		}

		synchronized (loadLock) {
			cached = structureCache.get(cacheKey);
			if (cached != null) {
				return cached;
			}
			try {
				Map<String, Object> root;
				try (InputStream stream = pack.getLoader().get(filePath)) {
					if (stream == null) {
						LOGGER.warning("NBT structure file not found: " + filePath + " in pack " + pack.getID());
						return null;
					}
					root = (Map<String, Object>) blueNBT.read(NBTUtil.detectDecompression(stream), TypeToken.of(Object.class));
				}
				List<Integer> sizeList = (List<Integer>) root.get("size");
				Vector3Int size = Vector3Int.of(sizeList.get(0), sizeList.get(1), sizeList.get(2));
				List<Map<String, Object>> paletteList = (List<Map<String, Object>>) root.get("palette");
				List<NBTStructure.PaletteEntry> palette = paletteList.stream()
						.map(p -> new NBTStructure.PaletteEntry(
								(String) p.get("Name"),
								(Map<String, String>) p.get("Properties")))
						.collect(Collectors.toList());
				List<Map<String, Object>> blockList = (List<Map<String, Object>>) root.get("blocks");
				// Lists to separate regular blocks from jigsaw blocks
				List<NBTStructure.BlockEntry> blocks = new ArrayList<>();
				List<JigsawData.JigsawBlock> jigsawBlocks = new ArrayList<>();
				// Process blocks and identify jigsaw blocks
				for (Map<String, Object> b : blockList) {
					List<Integer> posList = (List<Integer>) b.get("pos");
					Vector3Int pos = Vector3Int.of(posList.get(0), posList.get(1), posList.get(2));
					int state = ((Number) b.get("state")).intValue();
					Map<String, Object> nbt = (Map<String, Object>) b.get("nbt");
					// Check if this is a jigsaw block
					NBTStructure.PaletteEntry paletteEntry = palette.get(state);
					if ("minecraft:jigsaw".equals(paletteEntry.name())) {
						// Parse jigsaw block data
						if (nbt != null) {
							JigsawData.JigsawBlock jigsawBlock = parseJigsawBlock(pos, paletteEntry, nbt);
							if (jigsawBlock != null) {
								jigsawBlocks.add(jigsawBlock);
								LOGGER.fine(String.format("Found jigsaw block at %s: name=%s, target=%s", pos, jigsawBlock.info().name(),
										jigsawBlock.info().target()));
							}
						}
					} else {
						// Regular block
						blocks.add(new NBTStructure.BlockEntry(pos, state, nbt));
					}
				}
				// Include jigsawBlocks in StructureData constructor
				NBTStructure.StructureData structureData = new NBTStructure.StructureData(
						size, palette, blocks, jigsawBlocks);
				structureCache.put(cacheKey, structureData);
				if (!jigsawBlocks.isEmpty()) {
					LOGGER.info(String.format("Loaded structure %s with %d jigsaw connection points", filePath, jigsawBlocks.size()));
				}
				LOGGER.info("Successfully parsed and cached NBT structure: " + filePath + " from pack " + pack.getID());
				return structureData;
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, "Failed to read NBT file: " + filePath + " from pack " + pack.getID(), e);
				return null;
			} catch (Exception e) {
				LOGGER.log(Level.SEVERE, "Unexpected error parsing NBT file: " + filePath + " from pack " + pack.getID(), e);
				return null;
			}
		}
	}

	/**
	 * ADDED: Parses jigsaw block data from NBT.
	 */
	private JigsawData.JigsawBlock parseJigsawBlock(Vector3Int pos, NBTStructure.PaletteEntry paletteEntry,
			Map<String, Object> nbt) {
		try {
			// Extract jigsaw properties from NBT
			String name = (String) nbt.get("name");
			String target = (String) nbt.get("target");
			String pool = (String) nbt.get("pool");
			String joint = (String) nbt.getOrDefault("joint", "aligned");
			// Priority is stored as "selection_priority" in newer versions
			int priority = 0;
			if (nbt.containsKey("selection_priority")) {
				priority = ((Number) nbt.get("selection_priority")).intValue();
			}
			// Get orientation from block properties
			String orientation = "north"; // default
			if (paletteEntry.properties() != null) {
				orientation = paletteEntry.properties().getOrDefault("orientation", "north");
			}
			JigsawData.JigsawInfo info = new JigsawData.JigsawInfo(
					name != null ? name : "minecraft:empty",
					target != null ? target : "minecraft:empty",
					pool != null ? pool : "minecraft:empty",
					JigsawData.JointType.fromNbt(joint),
					priority);

			return new JigsawData.JigsawBlock(pos, orientation, info);
		} catch (Exception e) {
			LOGGER.warning("Failed to parse jigsaw block at " + pos + ": " + e.getMessage());
			return null;
		}
	}

	public void clearCache() {
		structureCache.clear();
		LOGGER.info("Cleared NBT structure cache.");
	}
}