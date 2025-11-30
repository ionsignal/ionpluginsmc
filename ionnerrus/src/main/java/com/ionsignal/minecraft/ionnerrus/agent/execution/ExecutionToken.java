package com.ionsignal.minecraft.ionnerrus.agent.execution;

import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * A read-only view of an execution lifecycle.
 * Passed downstream to Tasks, Skills, and Physical components.
 */
public class ExecutionToken {

    private final UUID id;
    private final ExecutionController controller;

    /**
     * A handle to a registered callback.
     * Essential for preventing memory leaks in long-running agents.
     */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        /**
         * Unregisters the callback. Idempotent.
         */
        @Override
        void close();
    }

    ExecutionToken(UUID id, ExecutionController controller) {
        this.id = id;
        this.controller = controller;
    }

    /**
     * Checks if the execution context is still active.
     */
    public boolean isActive() {
        return controller.isActive();
    }

    public UUID getId() {
        return id;
    }

    /**
     * Throws CancellationException if the token is inactive.
     * Used by async loops (e.g., Pathfinding) to abort early.
     */
    public void throwIfCancelled() throws CancellationException {
        if (!isActive()) {
            throw new CancellationException("Execution token " + id + " has been cancelled.");
        }
    }

    /**
     * Registers a cleanup action to be run when this token is cancelled.
     * Returns a Registration to allow unregistering when the operation completes successfully.
     * 
     * @param callback
     *            The action to run on cancellation.
     * @return A handle to unregister the callback.
     */
    public Registration onCancel(Runnable callback) {
        return controller.register(callback);
    }

    @Override
    public String toString() {
        return "Token{" + id.toString().substring(0, 8) + "}";
    }
}