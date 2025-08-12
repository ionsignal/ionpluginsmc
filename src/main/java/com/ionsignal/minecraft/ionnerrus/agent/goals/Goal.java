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
     * @param agent The agent executing the goal.
     * @return The next Task to execute, or null if the goal is complete.
     */
    void process(NerrusAgent agent);

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
}