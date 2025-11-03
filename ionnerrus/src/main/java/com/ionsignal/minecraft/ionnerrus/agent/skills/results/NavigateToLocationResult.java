package com.ionsignal.minecraft.ionnerrus.agent.skills.results;

/**
 * Describes the outcome of a navigation attempt at the skill level.
 */
public enum NavigateToLocationResult {
    /**
     * The agent successfully reached the target location.
     */
    SUCCESS,
    /**
     * No path to the target location could be found.
     */
    UNREACHABLE,
    /**
     * The agent got stuck and failed to follow the path.
     */
    STUCK,
    /**
     * The navigation was cancelled before completion.
     */
    CANCELLED
}