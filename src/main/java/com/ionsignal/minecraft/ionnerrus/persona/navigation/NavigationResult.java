package com.ionsignal.minecraft.ionnerrus.persona.navigation;

/**
 * Represents the result of a navigation attempt.
 */
public enum NavigationResult {
    /**
     * The persona successfully reached the target.
     */
    SUCCESS,

    /**
     * The navigation was cancelled.
     */
    CANCELLED,

    /**
     * The persona got stuck and could not reach the target.
     */
    STUCK,

    /**
     * A path to the target could not be found.
     */
    UNREACHABLE,

    /**
     * 
     */
    FAILURE
}