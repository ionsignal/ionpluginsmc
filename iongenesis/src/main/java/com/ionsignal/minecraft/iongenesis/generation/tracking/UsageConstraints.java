package com.ionsignal.minecraft.ionnerrus.terra.generation.tracking;

/**
 * Immutable record encapsulating min/max constraints for a pool element.
 * 
 * @param poolId
 *            The pool ID this constraint applies to
 * @param structureId
 *            The specific element ID
 * @param minCount
 *            Minimum required placements (0 = no minimum)
 * @param maxCount
 *            Maximum allowed placements (Integer.MAX_VALUE = no maximum)
 */
public record UsageConstraints(
        String poolId,
        String structureId,
        int minCount,
        int maxCount) {
    /**
     * Validation constructor.
     */
    public UsageConstraints {
        if (poolId == null || poolId.isEmpty()) {
            throw new IllegalArgumentException("Pool ID cannot be null or empty");
        }
        if (structureId == null || structureId.isEmpty()) {
            throw new IllegalArgumentException("Structure ID cannot be null or empty");
        }
        if (minCount < 0) {
            throw new IllegalArgumentException("Min count cannot be negative: " + minCount);
        }
        if (maxCount < minCount) {
            throw new IllegalArgumentException(
                    String.format("Max count (%d) cannot be less than min count (%d)", maxCount, minCount));
        }
    }

    /**
     * Creates constraints with no limits.
     */
    public static UsageConstraints unlimited(String poolId, String structureId) {
        return new UsageConstraints(poolId, structureId, 0, Integer.MAX_VALUE);
    }

    /**
     * Creates constraints with only a minimum.
     */
    public static UsageConstraints withMinimum(String poolId, String structureId, int minCount) {
        return new UsageConstraints(poolId, structureId, minCount, Integer.MAX_VALUE);
    }

    /**
     * Creates constraints with only a maximum.
     */
    public static UsageConstraints withMaximum(String poolId, String structureId, int maxCount) {
        return new UsageConstraints(poolId, structureId, 0, maxCount);
    }

    /**
     * Checks if this constraint has a minimum requirement.
     */
    public boolean hasMinimum() {
        return minCount > 0;
    }

    /**
     * Checks if this constraint has a maximum limit.
     */
    public boolean hasMaximum() {
        return maxCount < Integer.MAX_VALUE;
    }

    /**
     * Checks if a count satisfies this constraint.
     */
    public boolean isSatisfied(int currentCount) {
        return currentCount >= minCount && currentCount <= maxCount;
    }
}