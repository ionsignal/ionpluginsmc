package com.ionsignal.minecraft.ionnerrus.terra.generation.tracking;

/**
 * Immutable record encapsulating min/max constraints for a pool element.
 * 
 * @param poolId
 *            The pool ID this constraint applies to
 * @param elementFile
 *            The specific element file
 * @param minCount
 *            Minimum required placements (0 = no minimum)
 * @param maxCount
 *            Maximum allowed placements (Integer.MAX_VALUE = no maximum)
 */
public record UsageConstraints(
		String poolId,
		String elementFile,
		int minCount,
		int maxCount) {
	/**
	 * Validation constructor.
	 */
	public UsageConstraints {
		if (poolId == null || poolId.isEmpty()) {
			throw new IllegalArgumentException("Pool ID cannot be null or empty");
		}
		if (elementFile == null || elementFile.isEmpty()) {
			throw new IllegalArgumentException("Element file cannot be null or empty");
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
	public static UsageConstraints unlimited(String poolId, String elementFile) {
		return new UsageConstraints(poolId, elementFile, 0, Integer.MAX_VALUE);
	}

	/**
	 * Creates constraints with only a minimum.
	 */
	public static UsageConstraints withMinimum(String poolId, String elementFile, int minCount) {
		return new UsageConstraints(poolId, elementFile, minCount, Integer.MAX_VALUE);
	}

	/**
	 * Creates constraints with only a maximum.
	 */
	public static UsageConstraints withMaximum(String poolId, String elementFile, int maxCount) {
		return new UsageConstraints(poolId, elementFile, 0, maxCount);
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