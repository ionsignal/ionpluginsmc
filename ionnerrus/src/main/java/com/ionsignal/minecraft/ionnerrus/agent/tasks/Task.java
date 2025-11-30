package com.ionsignal.minecraft.ionnerrus.agent.tasks;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;

import java.util.concurrent.CompletableFuture;

public interface Task {
    /**
     * Executes the task.
     * 
     * @param agent
     *            The agent performing the task.
     * @param token
     *            The execution token bound to the lifecycle of the goal requesting this task.
     *            The task must pass this token down to any Skills it executes.
     * @return A future completing when the task is done.
     */
    CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token);

    /**
     * Called when a message is dispatched to the agent while this task is active allowing the Task to
     * handle tactical updates (like Repath requests) internally.
     * 
     * @param agent
     *            The agent.
     * @param message
     *            The message payload.
     * @param token
     *            The execution token.
     */
    default void onMessage(NerrusAgent agent, Object message, ExecutionToken token) {
        // Default no-op
    }
}