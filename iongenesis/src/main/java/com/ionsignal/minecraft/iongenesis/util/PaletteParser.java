package com.ionsignal.minecraft.iongenesis.util;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;
import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure.PaletteEntry;

import java.util.stream.Collectors;

/**
 * Utility for parsing NBT Palette entries into runtime BlockStates.
 * Replaces the parsing logic previously found in CoordinateConverter.
 */
public final class PaletteParser {

    private PaletteParser() {
        // Private constructor to prevent instantiation
    }

    /**
     * Parses a PaletteEntry into a Terra BlockState.
     * Constructs a string representation (e.g., "minecraft:chest[facing=north]")
     * and uses the Terra Platform API to create the BlockState object.
     *
     * @param entry
     *            The palette entry from the parsed NBT structure.
     * @param platform
     *            The Terra Platform instance, used for its world handle.
     * @return The corresponding Terra BlockState, or null on failure.
     */
    public static BlockState parsePaletteEntry(PaletteEntry entry, Platform platform) {
        try {
            StringBuilder sb = new StringBuilder(entry.name());
            if (entry.properties() != null && !entry.properties().isEmpty()) {
                String propertiesString = entry.properties().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(",", "[", "]"));
                sb.append(propertiesString);
            }
            return platform.getWorldHandle().createBlockState(sb.toString());
        } catch (Exception e) {
            return null; // Return null to indicate failure, allowing the caller to skip this block.
        }
    }
}