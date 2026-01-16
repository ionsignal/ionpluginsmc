package com.ionsignal.minecraft.ionnerrus.network.schema;

/**
 * Shared value objects used across both Ingress and Egress payloads.
 * These records ensure consistent data structures for spatial and physical data.
 */
public final class Shared {

    private Shared() {}

    /**
     * Represents a 3D vector, typically used for velocity or relative offsets.
     *
     * @param x The X component.
     * @param y The Y component.
     * @param z The Z component.
     */
    public record Vector3(
        double x,
        double y,
        double z
    ) {}

    /**
     * Represents a specific location within a Minecraft world.
     *
     * @param world The name of the world (e.g., "world", "world_nether").
     * @param x     The X coordinate.
     * @param y     The Y coordinate.
     * @param z     The Z coordinate.
     * @param yaw   The horizontal rotation.
     * @param pitch The vertical rotation.
     */
    public record LocationData(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {}
}