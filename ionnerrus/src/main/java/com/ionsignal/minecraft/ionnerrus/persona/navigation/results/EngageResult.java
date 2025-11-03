package com.ionsignal.minecraft.ionnerrus.persona.navigation.results;

/**
 * Represents the result of a Navigator's "engage" operation on a target.
 * This result focuses solely on the movement and state of the target, not whether it was successfully collected.
 */
public enum EngageResult {
    /**
     * The engage operation was successful from the Navigator's perspective.
     * This is an internal state used by the monitoring skill, which is responsible for determining the final success of the collection.
     */
    SUCCESS,

    /**
     * The target Item became invalid (e.g., despawned or picked up by another entity) during the engage operation.
     */
    TARGET_GONE,

    /**
     * The Navigator detected that it was unable to make progress towards the target's location.
     */
    STUCK,

    /**
     * The engage operation took too long to complete and was timed out by the calling skill.
     */
    TIMED_OUT,

    /**
     * The engage operation was cancelled by an external call (e.g., a new task was assigned).
     */
    CANCELLED
}