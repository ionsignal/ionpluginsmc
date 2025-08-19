package com.ionsignal.minecraft.ionnerrus.agent.skills.results;

/**
 * Describes the outcome of an item collection attempt.
 */
public enum CollectItemResult {
    /**
     * The agent successfully navigated to and collected the item.
     */
    SUCCESS,
    /**
     * The target item was already gone (despawned or picked up by another entity) when the skill started.
     */
    ITEM_GONE,
    /**
     * A valid standing spot near the item could not be found.
     */
    NO_CANDIDATE_SPOTS,
    /**
     * A path to any of the valid standing spots could not be found.
     */
    NO_PATH_FOUND,
    /**
     * The agent failed to follow the path to the item.
     */
    NAVIGATION_FAILED,
    /**
     * The agent arrived at the location but failed to pick up the item within the timeout period.
     */
    PICKUP_TIMEOUT,
    /**
     * The owning task was cancelled during the skill's execution.
     */
    CANCELLED
}