package com.ionsignal.minecraft.ionnerrus.agent.sensory;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * A thread-safe, immutable snapshot of an entity's state at a specific tick used to pass entity
 * data to async threads without risking NMS/Bukkit race conditions or memory leaks.
 *
 * @param uuid
 *            The unique identifier of the entity.
 * @param type
 *            The type of the entity.
 * @param location
 *            A CLONED location snapshot (safe for async read).
 * @param velocity
 *            A CLONED velocity vector (safe for async read).
 * @param isVisualTarget
 *            Whether this entity is currently visible to the agent (calculated).
 */
public record SensoryEntity(
        UUID uuid,
        EntityType type,
        Location location,
        Vector velocity,
        boolean isVisualTarget) {
    // No logic methods needed for data carrier
}