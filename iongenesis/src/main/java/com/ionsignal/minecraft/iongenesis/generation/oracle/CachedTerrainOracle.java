package com.ionsignal.minecraft.iongenesis.generation.oracle;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Optional;

/**
 * A decorator for TerrainOracle that caches results for the lifespan of the oracle instance.
 */
public class CachedTerrainOracle implements TerrainOracle {
    private static final int CACHE_MISS = Integer.MAX_VALUE;
    private static final int RESULT_EMPTY = Integer.MIN_VALUE;

    private final TerrainOracle delegate;
    private final Long2IntOpenHashMap cache;

    public CachedTerrainOracle(TerrainOracle delegate) {
        this.delegate = delegate;
        this.cache = new Long2IntOpenHashMap();
        this.cache.defaultReturnValue(CACHE_MISS);
    }

    @Override
    public Optional<Integer> getSurfaceHeight(int x, int z) {
        long key = pack(x, z);
        int cachedValue = cache.get(key);
        // Hit
        if (cachedValue != CACHE_MISS) {
            return cachedValue == RESULT_EMPTY ? Optional.empty() : Optional.of(cachedValue);
        }
        // Miss - Delegate to actual oracle
        Optional<Integer> result = delegate.getSurfaceHeight(x, z);
        if (result.isPresent()) {
            cache.put(key, result.get().intValue());
        } else {
            cache.put(key, RESULT_EMPTY);
        }
        return result;
    }

    @Override
    public Optional<Integer> findSurface(int x, int startY, int z, int verticalSearchLimit) {
        long key = pack(x, z);
        int cachedValue = cache.get(key);
        // Hit - A cached value is absolute truth for (x, z)
        if (cachedValue != CACHE_MISS) {
            // If cached as "EMPTY" (Global Void), return empty.
            if (cachedValue == RESULT_EMPTY) {
                return Optional.empty();
            }
            // Check if the cached absolute height is within our local search limit.
            // If the cached surface is 50 blocks away, findSurface should return Empty (Obstructed),
            // even though we know where the surface is.
            if (Math.abs(cachedValue - startY) <= verticalSearchLimit) {
                return Optional.of(cachedValue);
            } else {
                // Cached global surface is out of range.
                // We must bypass cache and calculate, but NOT update the global cache.
            }
        }
        // Miss - Delegate to local search
        Optional<Integer> result = delegate.findSurface(x, startY, z, verticalSearchLimit);
        // The walker might find a cave floor or overhang (e.g., Y=20).
        // If we cache (x,z)->20, a future call to getSurfaceHeight() (Global Scan)
        // will return 20 instead of the real sky surface (e.g., Y=100).
        // We only cache Global Scans.
        return result;
    }

    /**
     * Packs x and z coordinates into a long key.
     */
    private static long pack(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | ((long) z << 32);
    }
}