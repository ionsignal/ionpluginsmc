package com.ionsignal.minecraft.iongenesis.generation.enforcement;

import com.ionsignal.minecraft.iongenesis.generation.tracking.UsageConstraints;

/**
 * Represents a violation of min/max count constraints after generation.
 * Used for reporting and diagnostics.
 * 
 * @param constraint
 *            The constraint that was violated
 * @param actualCount
 *            The actual number of pieces placed
 * @param violationType
 *            Whether this is a MIN or MAX violation
 */
public record ConstraintViolation(
        UsageConstraints constraint,
        int actualCount,
        ViolationType violationType) {

    public enum ViolationType {
        MINIMUM_NOT_MET, MAXIMUM_EXCEEDED
    }

    /**
     * Formats a human-readable message.
     */
    public String getMessage() {
        return switch (violationType) {
            case MINIMUM_NOT_MET -> String.format(
                    "Minimum not met: %s from pool %s (required: %d, actual: %d)",
                    constraint.structureId(),
                    constraint.poolId(),
                    constraint.minCount(),
                    actualCount);
            case MAXIMUM_EXCEEDED -> String.format(
                    "Maximum exceeded: %s from pool %s (limit: %d, actual: %d)",
                    constraint.structureId(),
                    constraint.poolId(),
                    constraint.maxCount(),
                    actualCount);
        };
    }
}