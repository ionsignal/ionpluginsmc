package com.ionsignal.minecraft.ionnerrus.persona.orientation;

import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl.BodyMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/**
 * Represents a structured request for how the Persona should orient itself.
 * Used to arbitrate between High-Level Cognition (Skills) and Low-Level Physics (Maneuvers).
 */
public interface OrientationIntent {

    /**
     * Look at a specific block location (e.g., interacting, pathing).
     */
    record FocusOnLocation(Location target, BodyMode mode) implements OrientationIntent {
    }

    /**
     * Track a dynamic entity (e.g., combat, following).
     */
    record TrackEntity(Entity target, BodyMode mode) implements OrientationIntent {
    }

    /**
     * Align to a specific vector (e.g., Drop, Jump, Elytra flight).
     *
     * @param heading
     *            The vector to face.
     * @param snap
     *            If true, rotation is applied instantly (bypassing interpolation).
     * @param mode
     *            The body rotation mode.
     */
    record AlignToHeading(Vector heading, boolean snap, BodyMode mode) implements OrientationIntent {
    }

    /**
     * No specific orientation required (Idle animations take over).
     */
    record Idle() implements OrientationIntent {
    }
}