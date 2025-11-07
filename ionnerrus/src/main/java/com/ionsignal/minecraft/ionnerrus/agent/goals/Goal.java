package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

public interface Goal {
    /**
     * Called when the goal is first assigned to the agent.
     * Use this to set up the initial state or speak an introductory message.
     */
    void start(NerrusAgent agent);

    /**
     * Called by the agent to determine the next task to execute.
     * The implementation can use the agent's state and blackboard to make a
     * decision.
     * 
     * @param agent
     *            The agent executing the goal.
     * @return The next Task to execute, or null if the goal is complete.
     */
    void process(NerrusAgent agent);

    /**
     * Called on a parent goal when a sub-goal it requested has finished.
     * The default implementation does nothing, so only goals that use sub-goaling need to implement it.
     *
     * @param agent
     *            The agent executing the goal.
     * @param subGoalResult
     *            The result of the completed sub-goal.
     */
    default void resume(NerrusAgent agent, GoalResult subGoalResult) {
        // Default implementation is empty.
    }

    /**
     * Called when a message is dispatched to this goal from an async operation.
     * This method is always invoked on the main server thread, making it safe to mutate goal state.
     * 
     * Async callbacks should post messages via {@link NerrusAgent#postMessage(Object)} rather than
     * directly mutating goal state. The default implementation does nothing.
     *
     * @param agent
     *            The agent executing the goal.
     * @param message
     *            The message payload (commonly {@link GoalResult} for async operation outcomes).
     */
    default void onMessage(NerrusAgent agent, Object message) {
        // Default implementation is empty - override in goals that need message handling.
    }

    /**
     * @return True if the goal has been completed or cannot continue.
     */
    boolean isFinished();

    /**
     * Called when the agent is assigned a new goal or is being shut down.
     * Use this for any cleanup logic.
     */
    void stop(NerrusAgent agent);

    /**
     * Gets the final result of the goal after it has finished.
     * This should only be called when isFinished() returns true.
     *
     * @return A GoalResult object containing the outcome and a descriptive message.
     */
    GoalResult getFinalResult();

    /**
     * Gets the goals unique identifier
     *
     * @return A Object that acts as an instance identifier for this goal.
     */
    Object getContextToken();
}