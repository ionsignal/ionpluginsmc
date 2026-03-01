package com.ionsignal.minecraft.ionnerrus.agent.cognition.sensory;

/**
 * Configuration parameters for the sensory system which defines the physical limits of the agent's
 * perception around it.
 */
public record SensoryConfig(
        double viewDistanceBlocks,
        double fieldOfViewDegrees,
        long memoryRetentionMs) {
    /**
     * Default configuration for a standard humanoid agent.
     * 20 block radius, 110 degree FOV, 1 second memory retention.
     */
    public static final SensoryConfig DEFAULT = new SensoryConfig(20.0, 110.0, 1000L);
}