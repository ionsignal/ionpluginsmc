package com.ionsignal.minecraft.iongenesis.generation.logic;

/**
 * Immutable data carrier for the results of topological terrain analysis.
 * Provides detailed context about the terrain shape, obstructions, and trends
 * to drive intelligent piece selection.
 *
 * @param trend
 *            The overall classification (RISING, FALLING, FLAT).
 * @param averageY
 *            The average surface height of the sampled grid.
 * @param minDelta
 *            The lowest Y-difference relative to the connection Y.
 * @param maxDelta
 *            The highest Y-difference relative to the connection Y.
 * @param isObstructed
 *            True if the walker hit a vertical wall (stepped up > limit).
 * @param isCliff
 *            True if the walker hit a drop-off (stepped down > limit).
 */
public record TerrainContext(
        TerrainTrend trend,
        double averageY,
        int minDelta,
        int maxDelta,
        boolean isObstructed,
        boolean isCliff) {

    /**
     * Checks if the terrain is valid for normal traversal.
     *
     * @return true if neither obstructed nor a cliff.
     */
    public boolean isTraversable() {
        return !isObstructed && !isCliff;
    }

    /**
     * Creates a default context representing flat, valid terrain.
     * Used for fallbacks or when adaptation is disabled.
     *
     * @param y
     *            The reference Y level.
     * @return A neutral TerrainContext.
     */
    public static TerrainContext flat(int y) {
        return new TerrainContext(TerrainTrend.FLAT, y, 0, 0, false, false);
    }
}