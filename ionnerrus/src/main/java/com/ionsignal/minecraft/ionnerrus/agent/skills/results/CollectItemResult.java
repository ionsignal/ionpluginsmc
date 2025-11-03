package com.ionsignal.minecraft.ionnerrus.agent.skills.results;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * A rich result object for the CollectItemSkill.
 * It provides a status indicating the outcome of the collection attempt and, on success,
 * the specific item stack that was collected.
 */
public record CollectItemResult(Status status, Optional<ItemStack> collectedItem) {
    /**
     * Describes the outcome of the item collection attempt.
     */
    public enum Status {
        /**
         * The agent successfully navigated to and collected the item.
         */
        SUCCESS,
        /**
         * The target item was already gone. For the GatherBlockTask, this is often treated as a success.
         */
        ITEM_GONE,
        /**
         * A valid standing spot near the item could not be found.
         */
        NO_STANDPOINTS_FOUND,
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

    /**
     * Creates a success result with the specified item stack.
     */
    public static CollectItemResult success(ItemStack collectedItem) {
        return new CollectItemResult(Status.SUCCESS, Optional.of(collectedItem));
    }

    /**
     * Creates a failure or non-success result with the specified reason.
     */
    public static CollectItemResult failure(Status reason) {
        if (reason == Status.SUCCESS) {
            throw new IllegalArgumentException("Failure result cannot have SUCCESS status.");
        }
        return new CollectItemResult(reason, Optional.empty());
    }
}