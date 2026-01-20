package com.ionsignal.minecraft.iongenesis.generation.oracle;

import java.util.Optional;

/**
 * A simple oracle that returns a constant height.
 * Used as a fallback when the world generator is not supported, for flat worlds, or for debugging.
 */
public class FlatOracle implements TerrainOracle {
    private final int height;

    public FlatOracle(int height) {
        this.height = height;
    }

    @Override
    public Optional<Integer> getSurfaceHeight(int x, int z) {
        return Optional.of(height);
    }
}