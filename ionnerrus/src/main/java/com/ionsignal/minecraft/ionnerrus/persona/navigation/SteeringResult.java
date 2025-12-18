package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import java.util.Optional;

/**
 * Represents the immediate control intent calculated by the Navigator/PathFollower.
 * Consumed by the LocomotionController.
 */
public record SteeringResult(
        Location target,
        MovementType movementType,
        Optional<Vector> exitHeading) {
    public enum MovementType {
        WALK, // Continuous
        JUMP, DROP, // Regular maneuvers
        SWIM, // General submerged movement
        WADE, // Feet in water, head in air/shallow (Walking physics)
        WATER_EXIT; // Transition from water to land
    }

    public SteeringResult(Location target, MovementType movementType) {
        this(target, movementType, Optional.empty());
    }
}