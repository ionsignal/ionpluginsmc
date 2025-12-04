package com.ionsignal.minecraft.ionnerrus.persona;

/**
 * A centralized repository for Persona metadata keys.
 */
public final class MetadataKeys {
    private MetadataKeys() {
    }

    /**
     * The movement speed of the Persona in blocks per tick.
     * <p>
     * Type: {@link Double}
     * Default: 0.22
     */
    public static final String MOVEMENT_SPEED = "movement_speed";

    /**
     * The maximum distance (in blocks) the Persona can interact with blocks.
     * <p>
     * Type: {@link Double}
     * Default: 4.5 (Vanilla Survival)
     */
    public static final String BLOCK_REACH = "block_reach";
}