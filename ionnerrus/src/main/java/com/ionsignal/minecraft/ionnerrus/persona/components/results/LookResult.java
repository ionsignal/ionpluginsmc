package com.ionsignal.minecraft.ionnerrus.persona.components.results;

/**
 * Describes the outcome of an orientation (look/track) operation.
 */
public enum LookResult {
    /**
     * The agent successfully converged on the target angles.
     */
    SUCCESS,
    /**
     * The operation timed out before convergence could be reached.
     */
    TIMEOUT,
    /**
     * The target (e.g., an entity) became invalid or disappeared.
     */
    TARGET_LOST,
    /**
     * The operation was explicitly cancelled by a new command or stop request.
     */
    CANCELLED
}