package com.ionsignal.minecraft.iongenesis.model.structure;

import java.util.List;
import java.util.Map;

import com.dfsek.seismic.type.vector.Vector3Int;

/**
 * Contains immutable records for holding the parsed data of a Minecraft NBT structure file.
 * This model is completely independent of NMS (net.minecraft.server) and provides a clean,
 * thread-safe representation of a structure.
 */
public final class NBTStructure {

    /** Private constructor to prevent instantiation of the container class. */
    private NBTStructure() {
    }

    /**
     * Represents the entire structure data parsed from an NBT file.
     *
     * @param size
     *            The dimensions of the structure.
     * @param palette
     *            The list of block states used in the structure.
     * @param blocks
     *            The list of blocks with their positions and palette indices.
     * @param jigsawBlocks
     *            The list of jigsaw connection points in the structure. // ADDED: Jigsaw blocks field
     */
    public record StructureData(
            Vector3Int size,
            List<PaletteEntry> palette,
            List<BlockEntry> blocks,
            List<JigsawData.JigsawBlock> jigsawBlocks // ADDED: List of jigsaw blocks
    ) {
    }

    /**
     * Represents a single entry in the structure's palette.
     *
     * @param name
     *            The block's namespaced ID (e.g., "minecraft:chest").
     * @param properties
     *            A map of block state properties (e.g., {"facing": "north"}). Can be null if no
     *            properties exist.
     */
    public record PaletteEntry(String name, Map<String, String> properties) {
    }

    /**
     * Represents a single block within the structure.
     *
     * @param pos
     *            The relative position of the block within the structure's bounds.
     * @param state
     *            The index into the palette for this block's state.
     * @param nbt
     *            NBT data for the block entity, if any. Can be null.
     */
    public record BlockEntry(Vector3Int pos, int state, Map<String, Object> nbt) {
    }
}