package com.ionsignal.minecraft.ionnerrus.agent.skills.results;

/**
 * Describes the outcome of a block-breaking attempt.
 */
public enum BreakBlockResult {
    /**
     * The block was successfully broken.
     */
    SUCCESS,
    /**
     * The block was already air when the skill was executed.
     */
    ALREADY_BROKEN,
    /**
     * The agent was too far away to break the block.
     */
    OUT_OF_REACH,
    /**
     * The agent's line of sight to the block was obstructed by another solid block.
     */
    OBSTRUCTED,
    /**
     * The underlying break action failed for another reason (e.g., cancellation, world protection).
     */
    ACTION_FAILED
}