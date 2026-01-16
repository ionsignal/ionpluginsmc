package com.ionsignal.minecraft.iongenesis.generation.placements;

import com.dfsek.seismic.type.vector.Vector3Int;
import com.ionsignal.minecraft.iongenesis.model.structure.JigsawData;

/**
 * Represents a jigsaw connection point that has been transformed from structure-local
 * coordinates to absolute world coordinates. This is used during structure generation
 * to track available connection points on already-placed pieces.
 * 
 * @param position
 *            The absolute world position of this jigsaw block
 * @param orientation
 *            The world-space orientation of this jigsaw (e.g., "north", "east_up")
 * @param info
 *            The jigsaw connection information (name, target pool, etc.)
 */
public record TransformedJigsawBlock(
        Vector3Int position,
        String orientation,
        JigsawData.JigsawInfo info) {

    /**
     * Creates a TransformedJigsawBlock with validation.
     */
    public TransformedJigsawBlock {
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }
        if (orientation == null || orientation.isEmpty()) {
            throw new IllegalArgumentException("Orientation cannot be null or empty");
        }
        if (info == null) {
            throw new IllegalArgumentException("JigsawInfo cannot be null");
        }
    }

    public int getPriority() {
        int basePriority = info.placementPriority();
        // Boost priority for vertical connections as they often define structure height
        if ("up".equals(orientation) || "down".equals(orientation)) {
            basePriority += 10;
        }
        // Boost priority for connections with specific (non-wildcard) targets
        if (!info.target().contains("*") && !info.target().endsWith(":empty")) {
            basePriority += 5;
        }
        return basePriority;
    }

    /**
     * Checks if this jigsaw can connect to a given target name.
     * 
     * @param targetName
     *            The name to check against
     * @return true if this jigsaw's target matches the given name
     */
    public boolean canConnectTo(String targetName) {
        if ("minecraft:empty".equals(info.target())) {
            return false;
        }
        // Exact match
        if (info.target().equals(targetName)) {
            return true;
        }
        // Wildcard matching
        if (info.target().contains("*")) {
            String pattern = info.target().replace("*", ".*");
            return targetName.matches(pattern);
        }
        // Prefix matching (e.g., "village:street" matches "village:street_01")
        if (targetName.startsWith(info.target() + "_")) {
            return true;
        }

        return false;
    }

    /**
     * Converts this transformed jigsaw back to a regular JigsawBlock for use with JigsawConnection
     * validation. The position is preserved, but the validation logic uses orientation and info.
     *
     * @return A JigsawBlock representation of this transformed connection
     */
    public JigsawData.JigsawBlock toJigsawBlock() {
        return new JigsawData.JigsawBlock(position, orientation, info);
    }
}