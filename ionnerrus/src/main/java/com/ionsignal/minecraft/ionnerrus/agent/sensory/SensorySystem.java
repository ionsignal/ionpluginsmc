package com.ionsignal.minecraft.ionnerrus.agent.sensory;

/**
 * Defines the contract for the agent's perception system.
 * Responsible for maintaining an up-to-date model of the world (WorkingMemory).
 */
public interface SensorySystem {

    /**
     * The heartbeat of the sensory system.
     * Must be called once per server tick on the main thread.
     * Orchestrates the Gather (Main) -> Process (Async) -> Update (Atomic) cycle.
     */
    void tick();

    /**
     * Retrieves the most recent complete snapshot of the world.
     * This operation is lock-free and returns immediately.
     *
     * @return The current WorkingMemory.
     */
    WorkingMemory getWorkingMemory();

    /**
     * Checks if the current memory snapshot is older than the configured retention threshold.
     *
     * @return true if the data is considered stale/unreliable.
     */
    boolean isStale();

    /**
     * Updates the configuration for this sensory system.
     *
     * @param config
     *            The new configuration parameters.
     */
    void configure(SensoryConfig config);
}