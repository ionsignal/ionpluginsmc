package com.ionsignal.minecraft.ionnerrus.agent.cognition.sensory;

import java.util.List;
import java.util.Optional;

/**
 * Represents the agent's total awareness of the world at a specific moment in time swapped
 * atomically by the SensorySystem.
 *
 * @param visibleEntities
 *            List of safe entity snapshots detected nearby.
 * @param attentionTarget
 *            The entity the agent finds most interesting (if any).
 * @param timeOfDay
 *            The world time at the moment of capture.
 * @param timestamp
 *            The system time (ms) when this memory was formed.
 */
public record WorkingMemory(
        List<SensoryEntity> visibleEntities,
        Optional<SensoryEntity> attentionTarget,
        long timeOfDay,
        long timestamp) {

    /**
     * A safe default for an agent that has not yet perceived the world.
     */
    public static final WorkingMemory EMPTY = new WorkingMemory(List.of(), Optional.empty(), 0L, 0L);
}