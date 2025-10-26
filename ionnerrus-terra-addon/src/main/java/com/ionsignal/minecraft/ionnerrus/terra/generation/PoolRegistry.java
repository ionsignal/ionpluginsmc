package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.dfsek.terra.api.registry.Registry;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Registry wrapper for accessing JigsawPool instances from a ConfigPack.
 * Provides a clean API for fetching pools by their string ID.
 */
public class PoolRegistry {
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

		// Parse the pool ID into a RegistryKey
		RegistryKey poolKey;
		try {
			if (poolId.contains(":")) {
				String[] parts = poolId.split(":", 2);
				poolKey = RegistryKey.of(parts[0], parts[1]);
			} else {
				// Default to ionnerrus namespace if not specified
				poolKey = RegistryKey.of("ionnerrus", poolId);
			}
		} catch (Exception e) {
			// Log warning about invalid pool ID format
			return null;
		}

		// Query the pack's registry for JigsawPool
		try {
			Registry<JigsawPool> registry = pack.getRegistry(JigsawPool.class);
			Optional<JigsawPool> pool = registry.get(poolKey);

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
	 * Clears the internal cache.
	 */
	public void clearCache() {
		poolCache.clear();
	}
}