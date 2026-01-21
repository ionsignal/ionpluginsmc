package com.ionsignal.minecraft.iongenesis.generation.oracle;

import java.util.Optional;

/**
 * Abstraction for querying terrain height during structure planning.
 * Allows the planner to be decoupled from the specific world generator implementation.
 */
public interface TerrainOracle {
    /**
     * Gets the Y-coordinate of the terrain surface at the given X/Z coordinates.
     * Performs a global scan from the world ceiling.
     *
     * @param x
     *            The absolute X coordinate.
     * @param z
     *            The absolute Z coordinate.
     * @return An Optional containing the Y height, or empty if it cannot be determined.
     */
    Optional<Integer> getSurfaceHeight(int x, int z);

    /**
     * Attempts to find the surface Y level at (x, z) by searching vertically
     * starting from startY.
     *
     * @param x
     *            Target X
     * @param startY
     *            The Y level to start searching from (usually the connector Y)
     * @param z
     *            Target Z
     * @param verticalSearchLimit
     *            Max blocks to search up/down before giving up
     * @return Optional containing the surface Y, or Empty if obstructed/void.
     */
    Optional<Integer> findSurface(int x, int startY, int z, int verticalSearchLimit);
}