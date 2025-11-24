package com.ionsignal.minecraft.ionnerrus.persona.components.results;

/**
 * Describes the outcome of a physical action (block interaction, animation, etc.).
 */
public enum ActionResult {
    /**
     * The action completed successfully.
     */
    SUCCESS,
    /**
     * The action failed due to world conditions (e.g., obstruction, invalid target).
     */
    FAILURE,
    /**
     * The action was cancelled before completion.
     */
    CANCELLED
}