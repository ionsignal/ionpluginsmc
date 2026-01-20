package com.ionsignal.minecraft.iongenesis.generation.logic;

/**
 * Represents the trend of the terrain ahead of a connection point.
 * Used by the CandidateSelector to bias selection towards pieces that fit the terrain.
 */
public enum TerrainTrend {
    /**
     * The terrain is roughly at the same level as the connection.
     * Standard selection weights apply.
     */
    FLAT,

    /**
     * The terrain rises significantly ahead (e.g., a cliff or hill).
     * Selector should prefer pieces with a positive vertical delta (Ramps Up).
     */
    RISING,

    /**
     * The terrain drops significantly ahead (e.g., a decline or hole).
     * Selector should prefer pieces with a negative vertical delta (Ramps Down).
     */
    FALLING
}