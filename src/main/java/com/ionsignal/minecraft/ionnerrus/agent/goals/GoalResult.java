package com.ionsignal.minecraft.ionnerrus.agent.goals;

/**
 * An immutable record that encapsulates the final result of a Goal's execution.
 * This provides a formal, type-safe contract for communication between the
 * agent's
 * goal-processing system and the higher-level cognitive director.
 *
 * @param status  The final status of the goal (SUCCESS or FAILURE).
 * @param message A descriptive message intended for the LLM, explaining the
 *                outcome.
 */
public record GoalResult(Status status, String message) {

    /**
     * Represents the terminal state of a Goal.
     */
    public enum Status {
        SUCCESS,
        FAILURE
    }
}