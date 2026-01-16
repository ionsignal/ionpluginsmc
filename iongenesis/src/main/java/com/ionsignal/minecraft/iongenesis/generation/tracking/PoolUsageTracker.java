package com.ionsignal.minecraft.iongenesis.generation.tracking;

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
     * @param structureId
     *            The NBT element structure ID
     */
    public void recordPlacement(String poolId, String structureId) {
        if (poolId == null) {
            poolId = "_simple_structure";
        }
        usageMap.computeIfAbsent(poolId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(structureId, k -> new AtomicInteger(0))
                .incrementAndGet();
        totalPieces.incrementAndGet();
    }

    /**
     * Gets the current count for a specific pool element.
     * 
     * @param poolId
     *            The pool ID
     * @param structureId
     *            The NBT element structure ID
     * @return The current count, or 0 if not tracked
     */
    public int getCount(String poolId, String structureId) {
        ConcurrentHashMap<String, AtomicInteger> poolMap = usageMap.get(poolId);
        if (poolMap == null)
            return 0;
        AtomicInteger count = poolMap.get(structureId);
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
     * @param structureId
     *            The NBT element structure ID
     * @param minCount
     *            The minimum required count
     * @return true if requirement is met
     */
    public boolean meetsMinimum(String poolId, String structureId, int minCount) {
        return getCount(poolId, structureId) >= minCount;
    }

    /**
     * Checks if usage has reached maximum limit.
     * 
     * @param poolId
     *            The pool ID
     * @param structureId
     *            The NBT element structure ID
     * @param maxCount
     *            The maximum allowed count
     * @return true if limit is reached
     */
    public boolean reachedMaximum(String poolId, String structureId, int maxCount) {
        return getCount(poolId, structureId) >= maxCount;
    }
}