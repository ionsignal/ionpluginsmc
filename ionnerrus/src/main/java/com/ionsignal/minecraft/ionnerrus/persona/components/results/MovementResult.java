package com.ionsignal.minecraft.ionnerrus.persona.components.results;

/**
 * Describes the outcome of a movement operation (navigation, engaging, or following).
 * Replaces NavigationResult and EngageResult.
 */
public enum MovementResult {
    /**
     * The movement completed successfully (reached target, engaged entity).
     */
    SUCCESS,
    /**
     * The target location is unreachable or invalid.
     */
    UNREACHABLE,
    /**
     * The agent got stuck and failed to make progress.
     */
    STUCK,
    /**
     * The dynamic target (e.g., entity) disappeared or became invalid.
     */
    TARGET_LOST,
    /**
     * The operation timed out.
     */
    TIMEOUT,
    /**
     * The operation was cancelled by a new command or stop request.
     */
    CANCELLED,
    /**
     * A generic failure occurred.
     */
    FAILURE
}