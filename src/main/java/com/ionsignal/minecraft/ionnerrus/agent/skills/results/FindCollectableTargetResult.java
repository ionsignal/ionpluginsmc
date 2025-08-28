package com.ionsignal.minecraft.ionnerrus.agent.skills.results;

import com.ionsignal.minecraft.ionnerrus.agent.skills.CollectableBlock;
import java.util.Optional;

/**
 * A rich result object for the FindCollectableTargetSkill.
 * It provides a status indicating the outcome of the search and, on success,
 * the optimal target that was found.
 */
public record FindCollectableTargetResult(Status status, Optional<CollectableBlock> target) {
    /**
     * Describes the outcome of the search for a collectable target.
     */
    public enum Status {
        /**
         * A valid, pathable target was found.
         */
        SUCCESS,
        /**
         * No blocks of the desired material were found in the search area.
         */
        NO_TARGETS_FOUND,
        /**
         * Target blocks were found, but none had a valid standing spot within reach.
         */
        NO_STANDPOINTS_FOUND,
        /**
         * Reachable standpoints were found, but the A* pathfinder could not find a
         * valid path to any of them.
         */
        NO_PATH_FOUND
    }

    /**
     * Creates a success result with the specified target.
     * 
     * @param target
     *            The found collectable target.
     * @return A new FindCollectableTargetResult instance.
     */
    public static FindCollectableTargetResult success(CollectableBlock target) {
        return new FindCollectableTargetResult(Status.SUCCESS, Optional.of(target));
    }

    /**
     * Creates a failure result with the specified reason.
     * 
     * @param reason
     *            The reason for the failure.
     * @return A new FindCollectableTargetResult instance.
     */
    public static FindCollectableTargetResult failure(Status reason) {
        if (reason == Status.SUCCESS) {
            throw new IllegalArgumentException("Failure result cannot have SUCCESS status.");
        }
        return new FindCollectableTargetResult(reason, Optional.empty());
    }
}