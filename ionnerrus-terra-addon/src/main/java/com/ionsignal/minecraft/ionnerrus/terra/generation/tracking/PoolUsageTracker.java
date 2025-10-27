package com.ionsignal.minecraft.ionnerrus.terra.generation.tracking;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Thread-safe tracker for monitoring which structure pieces are placed from which pools.
 * Used for enforcing min/max constraints and generating statistics.
 * 
 * Uses ConcurrentHashMap and AtomicInteger for lock-free tracking.
 */
public class PoolUsageTracker {
	private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> usageMap;
	private final AtomicInteger totalPieces;

	public PoolUsageTracker() {
		this.usageMap = new ConcurrentHashMap<>();
		this.totalPieces = new AtomicInteger(0);
	}

	/**
	 * Records a piece placement.
	 * 
	 * @param poolId
	 *            The source pool ID (can be null for simple structures)
	 * @param elementFile
	 *            The NBT file path
	 */
	public void recordPlacement(String poolId, String elementFile) {
		// Handle null poolId for simple (non-jigsaw) structures
		if (poolId == null) {
			poolId = "_simple_structure"; // Sentinel value
		}
		usageMap.computeIfAbsent(poolId, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(elementFile, k -> new AtomicInteger(0))
				.incrementAndGet();
		totalPieces.incrementAndGet();
	}

	/**
	 * Gets the current count for a specific pool element.
	 * 
	 * @param poolId
	 *            The pool ID
	 * @param elementFile
	 *            The element file
	 * @return The current count, or 0 if not tracked
	 */
	public int getCount(String poolId, String elementFile) {
		ConcurrentHashMap<String, AtomicInteger> poolMap = usageMap.get(poolId);
		if (poolMap == null)
			return 0;

		AtomicInteger count = poolMap.get(elementFile);
		return count == null ? 0 : count.get();
	}

	/**
	 * Gets total count for all elements in a pool.
	 * 
	 * @param poolId
	 *            The pool ID
	 * @return Total pieces from this pool
	 */
	public int getPoolTotal(String poolId) {
		ConcurrentHashMap<String, AtomicInteger> poolMap = usageMap.get(poolId);
		if (poolMap == null)
			return 0;

		return poolMap.values().stream()
				.mapToInt(AtomicInteger::get)
				.sum();
	}

	/**
	 * Gets a snapshot of all usage data.
	 * 
	 * @return Immutable map of pool -> element -> count
	 */
	public Map<String, Map<String, Integer>> getSnapshot() {
		return usageMap.entrySet().stream()
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						entry -> entry.getValue().entrySet().stream()
								.collect(Collectors.toMap(
										Map.Entry::getKey,
										e -> e.getValue().get()))));
	}

	/**
	 * Gets the total number of pieces tracked.
	 */
	public int getTotalPieces() {
		return totalPieces.get();
	}

	/**
	 * Checks if usage meets minimum requirements.
	 * 
	 * @param poolId
	 *            The pool ID
	 * @param elementFile
	 *            The element file
	 * @param minCount
	 *            The minimum required count
	 * @return true if requirement is met
	 */
	public boolean meetsMinimum(String poolId, String elementFile, int minCount) {
		return getCount(poolId, elementFile) >= minCount;
	}

	/**
	 * Checks if usage has reached maximum limit.
	 * 
	 * @param poolId
	 *            The pool ID
	 * @param elementFile
	 *            The element file
	 * @param maxCount
	 *            The maximum allowed count
	 * @return true if limit is reached
	 */
	public boolean reachedMaximum(String poolId, String elementFile, int maxCount) {
		return getCount(poolId, elementFile) >= maxCount;
	}
}