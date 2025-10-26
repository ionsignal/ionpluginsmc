package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.JigsawPlacement;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Thread-safe cache for jigsaw structure placements.
 * Ensures that each unique structure is only generated once, even in multi-threaded chunk
 * generation.
 */
public class JigsawPlacementCache {
	private static final JigsawPlacementCache INSTANCE = new JigsawPlacementCache();

	// Main cache storage
	private final ConcurrentHashMap<PlacementCacheKey, JigsawPlacement> cache = new ConcurrentHashMap<>();

	// Per-key locks to prevent duplicate generation
	private final ConcurrentHashMap<PlacementCacheKey, Lock> locks = new ConcurrentHashMap<>();

	// Maximum cache size before eviction
	private static final int MAX_CACHE_SIZE = 100;

	private JigsawPlacementCache() {
		// Private constructor for singleton
	}

	public static JigsawPlacementCache getInstance() {
		return INSTANCE;
	}

	/**
	 * Gets a cached placement or generates a new one if not present.
	 * Thread-safe with per-key locking to prevent duplicate generation.
	 * 
	 * @param key
	 *            The cache key identifying the placement
	 * @param generator
	 *            Supplier that generates the placement if not cached
	 * @return The cached or newly generated placement
	 */
	public JigsawPlacement getOrGenerate(PlacementCacheKey key, Supplier<JigsawPlacement> generator) {
		// Fast path: check if already cached
		JigsawPlacement cached = cache.get(key);
		if (cached != null) {
			return cached;
		}

		// Get or create a lock for this specific key
		Lock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());

		lock.lock();
		try {
			// Double-check after acquiring lock
			cached = cache.get(key);
			if (cached != null) {
				return cached;
			}

			// Generate the placement
			JigsawPlacement generated = generator.get();

			// Store in cache
			cache.put(key, generated);

			// Simple cache eviction if size exceeded
			if (cache.size() > MAX_CACHE_SIZE) {
				evictOldest();
			}

			return generated;

		} finally {
			lock.unlock();

			// Clean up lock if no longer needed
			if (!lock.tryLock()) {
				// Someone else is using it, keep it
			} else {
				try {
					// We got the lock, safe to remove if still unused
					locks.remove(key, lock);
				} finally {
					lock.unlock();
				}
			}
		}
	}

	/**
	 * Checks if a placement is already cached.
	 * 
	 * @param key
	 *            The cache key to check
	 * @return true if the placement is cached
	 */
	public boolean isCached(PlacementCacheKey key) {
		return cache.containsKey(key);
	}

	/**
	 * Clears the entire cache.
	 * Should only be called during server shutdown or world unload.
	 */
	public void clearCache() {
		cache.clear();
		locks.clear();
	}

	/**
	 * Gets the current cache size.
	 * 
	 * @return Number of cached placements
	 */
	public int getCacheSize() {
		return cache.size();
	}

	/**
	 * Simple eviction strategy: remove the first entry found.
	 * Could be improved with LRU or other strategies if needed.
	 */
	private void evictOldest() {
		// Remove one arbitrary entry
		var iterator = cache.entrySet().iterator();
		if (iterator.hasNext()) {
			var entry = iterator.next();
			cache.remove(entry.getKey());
			locks.remove(entry.getKey());
		}
	}
}