package com.ionsignal.minecraft.ionnerrus.agent.skills.results;

import org.bukkit.Location;
import java.util.Optional;

/**
 * Describes the outcome of a block-breaking attempt.
 */
public record BreakBlockResult(Status status, Optional<Location> obstruction) {

    public enum Status {
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

    public static BreakBlockResult success() {
        return new BreakBlockResult(Status.SUCCESS, Optional.empty());
    }

    public static BreakBlockResult alreadyBroken() {
        return new BreakBlockResult(Status.ALREADY_BROKEN, Optional.empty());
    }

    public static BreakBlockResult outOfReach() {
        return new BreakBlockResult(Status.OUT_OF_REACH, Optional.empty());
    }

    public static BreakBlockResult failure() {
        return new BreakBlockResult(Status.ACTION_FAILED, Optional.empty());
    }

    /**
     * Creates a result indicating the target is obstructed by another block.
     * 
     * @param location
     *            The location of the obstructing block.
     */
    public static BreakBlockResult obstructed(Location location) {
        return new BreakBlockResult(Status.OBSTRUCTED, Optional.of(location));
    }
}