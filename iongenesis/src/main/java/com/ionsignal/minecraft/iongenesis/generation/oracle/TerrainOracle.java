package com.ionsignal.minecraft.iongenesis.generation.oracle;

import java.util.Optional;

/**
 * Abstraction for querying terrain height during structure planning.
 * Allows the planner to be decoupled from the specific world generator implementation.
 */
public interface TerrainOracle {
    /**
     * Gets the Y-coordinate of the terrain surface at the given X/Z coordinates.
     *
     * @param x
     *            The absolute X coordinate.
     * @param z
     *            The absolute Z coordinate.
     * @return An Optional containing the Y height, or empty if it cannot be determined.
     */
    Optional<Integer> getSurfaceHeight(int x, int z);
}