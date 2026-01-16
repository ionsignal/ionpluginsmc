package com.ionsignal.minecraft.iongenesis.provider;

import com.dfsek.seismic.type.vector.Vector3Int;
import com.ionsignal.minecraft.iongenesis.model.JigsawData;
import com.ionsignal.minecraft.iongenesis.model.NBTStructure;
import com.ionsignal.minecraft.iongenesis.util.NBTUtil;

import de.bluecolored.bluenbt.BlueNBT;
import de.bluecolored.bluenbt.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Static utility for parsing NBT structure files.
 * Lifecycle and caching are now handled by the Terra Registry system.
 */
public class NBTStructureProvider {
    private static final BlueNBT blueNBT = new BlueNBT();
    private static final Logger LOGGER = Logger.getLogger(NBTStructureProvider.class.getName());

    private NBTStructureProvider() {
        // Utility class
    }

    /**
     * Parses an NBT structure from an InputStream.
     *
     * @param stream
     *            The input stream of the NBT file.
     * @param debugName
     *            Name of the file for debug logging.
     * @return The parsed StructureData, or null if loading failed.
     */
    @SuppressWarnings("unchecked")
    public static NBTStructure.StructureData parse(InputStream stream, String debugName) {
        try {
            Map<String, Object> root;
            try (InputStream is = stream) {
                root = (Map<String, Object>) blueNBT.read(NBTUtil.detectDecompression(is), TypeToken.of(Object.class));
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
            List<NBTStructure.BlockEntry> blocks = new ArrayList<>();
            List<JigsawData.JigsawBlock> jigsawBlocks = new ArrayList<>();
            for (Map<String, Object> b : blockList) {
                List<Integer> posList = (List<Integer>) b.get("pos");
                Vector3Int pos = Vector3Int.of(posList.get(0), posList.get(1), posList.get(2));
                int state = ((Number) b.get("state")).intValue();
                Map<String, Object> nbt = (Map<String, Object>) b.get("nbt");
                blocks.add(new NBTStructure.BlockEntry(pos, state, nbt));
                NBTStructure.PaletteEntry paletteEntry = palette.get(state);
                if ("minecraft:jigsaw".equals(paletteEntry.name())) {
                    if (nbt != null) {
                        JigsawData.JigsawBlock jigsawBlock = parseJigsawBlock(pos, paletteEntry, nbt);
                        if (jigsawBlock != null) {
                            jigsawBlocks.add(jigsawBlock);
                        }
                    }
                }
            }
            return new NBTStructure.StructureData(size, palette, blocks, jigsawBlocks);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to read NBT stream for: " + debugName, e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error parsing NBT: " + debugName, e);
            return null;
        }
    }

    private static JigsawData.JigsawBlock parseJigsawBlock(
            Vector3Int pos,
            NBTStructure.PaletteEntry paletteEntry,
            Map<String, Object> nbt) {
        try {
            String name = (String) nbt.get("name");
            String target = (String) nbt.get("target");
            String pool = (String) nbt.get("pool");
            String joint = (String) nbt.getOrDefault("joint", "aligned");
            String finalState = (String) nbt.getOrDefault("final_state", "minecraft:air");
            int priority = 0;
            if (nbt.containsKey("selection_priority")) {
                priority = ((Number) nbt.get("selection_priority")).intValue();
            }
            String orientation = "north";
            if (paletteEntry.properties() != null) {
                orientation = paletteEntry.properties().getOrDefault("orientation", "north");
            }
            JigsawData.JigsawInfo info = new JigsawData.JigsawInfo(
                    name != null ? name : "minecraft:empty",
                    target != null ? target : "minecraft:empty",
                    pool != null ? pool : "minecraft:empty",
                    JigsawData.JointType.fromNbt(joint),
                    priority,
                    finalState);
            return new JigsawData.JigsawBlock(pos, orientation, info);
        } catch (Exception e) {
            LOGGER.warning("Failed to parse jigsaw block at " + pos + ": " + e.getMessage());
            return null;
        }
    }
}