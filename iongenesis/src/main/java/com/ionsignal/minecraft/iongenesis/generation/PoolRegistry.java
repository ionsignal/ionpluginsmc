package com.ionsignal.minecraft.iongenesis.generation;

import com.ionsignal.minecraft.iongenesis.generation.tracking.UsageConstraints;
import com.ionsignal.minecraft.iongenesis.util.ResourceResolver;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.registry.Registry;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Registry wrapper for accessing JigsawPool instances from a ConfigPack.
 * Provides a clean API for fetching pools by their string ID.
 */
public class PoolRegistry {
    private static final Logger LOGGER = Logger.getLogger(PoolRegistry.class.getName());

    private final ConfigPack pack;
    private final Map<String, JigsawPool> poolCache;

    public PoolRegistry(ConfigPack pack) {
        this.pack = pack;
        this.poolCache = new ConcurrentHashMap<>();
    }

    /**
     * Gets a JigsawPool by its ID string.
     * 
     * @param poolId
     *            The pool ID (e.g., "ionnerrus:village_streets" or "minecraft:empty")
     * @return The JigsawPool, or null if not found
     */
    public JigsawPool getPool(String poolId) {
        if (poolId == null || poolId.isEmpty() || "minecraft:empty".equals(poolId)) {
            return null;
        }
        // Check cache first
        JigsawPool cached = poolCache.get(poolId);
        if (cached != null) {
            return cached;
        }
        // Use ResourceResolver for standardized lookup (Exact -> Fuzzy -> Fallback)
        try {
            Registry<JigsawPool> registry = pack.getRegistry(JigsawPool.class);
            Optional<JigsawPool> pool = ResourceResolver.resolve(registry, poolId, pack.getRegistryKey().getID());
            if (pool.isPresent()) {
                poolCache.put(poolId, pool.get());
                return pool.get();
            }
        } catch (Exception e) {
            // Registry might not exist or pool not found
            // This is expected for "minecraft:empty" and other special cases
        }
        return null;
    }

    /**
     * Checks if a pool exists in the registry.
     * 
     * @param poolId
     *            The pool ID to check
     * @return true if the pool exists, false otherwise
     */
    public boolean hasPool(String poolId) {
        return getPool(poolId) != null;
    }

    /**
     * Gathers usage constraints from registered pools with specific exception handling and logging
     */
    public List<UsageConstraints> getAllConstraints() {
        List<UsageConstraints> allConstraints = new ArrayList<>();
        try {
            // Query the Terra registry
            Registry<JigsawPool> registry = pack.getRegistry(JigsawPool.class);
            // More defensive iteration with error handling
            registry.forEach(pool -> {
                try {
                    allConstraints.addAll(pool.getAllConstraints());
                } catch (Exception e) {
                    // Log specific pool errors but continue processing ===
                    LOGGER.log(Level.WARNING, "Failed to get constraints from pool: " + pool.getId(), e);
                }
            });
        } catch (IllegalArgumentException e) {
            // No JigsawPool registry exists
            LOGGER.fine("No JigsawPool registry found in pack - this is normal if no pools are defined");
            return Collections.emptyList();
        } catch (NullPointerException e) {
            // Handling for NPE (likely programming error)
            LOGGER.log(Level.SEVERE, "Null pointer while accessing pool registry - this indicates a bug", e);
            return Collections.emptyList();
        } catch (Exception e) {
            // Log other exceptions as errors (not silently swallowed) ===
            LOGGER.log(Level.SEVERE, "Unexpected error gathering constraints from pool registry", e);
            return Collections.emptyList();
        }
        return allConstraints;
    }

    /**
     * Clears the internal cache.
     * Note: This only clears the cache, not the underlying Terra registry.
     */
    public void clearCache() {
        poolCache.clear();
    }
}