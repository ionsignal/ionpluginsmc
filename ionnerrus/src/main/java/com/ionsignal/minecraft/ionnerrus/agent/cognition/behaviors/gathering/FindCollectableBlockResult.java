package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.gathering;

import org.bukkit.Material;

import java.util.Optional;
import java.util.Set;

/**
 * A rich result object for the FindCollectableTargetSkill.
 * It provides a status indicating the outcome of the search and, on success,
 * the optimal target that was found.
 */
public record FindCollectableBlockResult(Status status, Optional<CollectableBlock> optimalTarget, Set<Material> allFoundMaterials) {
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
    public static FindCollectableBlockResult success(CollectableBlock target, Set<Material> allFound) {
        return new FindCollectableBlockResult(Status.SUCCESS, Optional.of(target), allFound);
    }

    /**
     * Creates a failure result with the specified reason.
     * 
     * @param reason
     *            The reason for the failure.
     * @return A new FindCollectableTargetResult instance.
     */
    public static FindCollectableBlockResult failure(Status reason, Set<Material> allFound) {
        if (reason == Status.SUCCESS) {
            throw new IllegalArgumentException("Failure result cannot have SUCCESS status.");
        }
        return new FindCollectableBlockResult(reason, Optional.empty(), allFound);
    }
}