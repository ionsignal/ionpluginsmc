package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Represents the immediate control intent calculated by the Navigator/PathFollower.
 * Consumed by the LocomotionController.
 */
public record SteeringResult(
        Location target,
        Vector desiredVelocity,
        MovementType movementType,
        boolean isArrived) {
    public enum MovementType {
        WALK, JUMP, DROP, SWIM
    }

    // Compact constructor for standard walking (backward compatibility)
    public SteeringResult(Location target, MovementType movementType) {
        this(target, new Vector(0, 0, 0), movementType, false);
    }

    // Constructor for swimming/3D movement
    public SteeringResult(Location target, MovementType movementType, Vector desiredVelocity) {
        this(target, desiredVelocity, movementType, false);
    }

    // Defensive copy for vector immutability
    public SteeringResult {
        if (desiredVelocity != null) {
            desiredVelocity = desiredVelocity.clone();
        } else {
            desiredVelocity = new Vector(0, 0, 0);
        }
    }
}