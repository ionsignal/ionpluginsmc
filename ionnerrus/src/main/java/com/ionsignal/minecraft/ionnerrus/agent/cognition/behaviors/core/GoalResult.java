package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core;

/**
 * A sealed interface representing the final result of a Goal's execution.
 * This provides a formal, type-safe contract for communication between the agent's
 * goal-processing system and the higher-level cognitive director.
 */
public sealed interface GoalResult {
    /**
     * A descriptive message intended for the LLM, explaining the outcome.
     * 
     * @return The result message.
     */
    String message();

    /**
     * Represents a successful goal completion.
     * 
     * @param message
     *            A descriptive message about the success.
     */
    record Success(String message) implements GoalResult {
    }

    /**
     * Represents a failed goal completion.
     * 
     * @param message
     *            A descriptive message explaining the failure.
     */
    record Failure(String message) implements GoalResult {
    }

    /**
     * Represents a goal that cannot continue until another goal (a prerequisite) is completed first.
     * 
     * @param message
     *            A descriptive message explaining why the prerequisite is needed.
     * @param prerequisite
     *            The definition of the goal that must be completed.
     */
    record PrerequisiteResult(String message, GoalPrerequisite prerequisite) implements GoalResult {
    }
}