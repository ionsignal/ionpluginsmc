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

    /**
     * Packs x and z coordinates into a long key.
     */
    private static long pack(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | ((long) z << 32);
    }
}