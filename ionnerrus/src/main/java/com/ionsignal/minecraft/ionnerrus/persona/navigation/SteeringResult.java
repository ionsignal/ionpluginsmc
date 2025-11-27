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
        MovementType movementType) {
    public enum MovementType {
        WALK, JUMP, DROP, SWIM;

        /**
         * Checks if the agent is within the "Completion Zone" of the target destination.
         * This determines if a maneuver (like a Jump) is effectively finished, even if
         * the path follower is still technically on the previous segment.
         *
         * @param agentPos
         *            The current location of the agent (feet).
         * @param targetPos
         *            The location of the destination node.
         * @return true if the agent has satisfied the geometric requirements of the maneuver.
         */
        public boolean isInsideCompletionBounds(Location agentPos, Location targetPos) {
            double dx = Math.abs(agentPos.getX() - targetPos.getX());
            double dz = Math.abs(agentPos.getZ() - targetPos.getZ());
            double dy = agentPos.getY() - targetPos.getY(); // Positive = Agent is above target
            switch (this) {
                case JUMP:
                    // Jump Completion Zone:
                    // Vertical: Must be at or above the target Y (with small epsilon for float errors).
                    // Horizontal: Must be within 1.2 blocks (generous to allow for momentum/overshoot).
                    // We treat Y as semi-infinite upwards because if we jumped HIGHER, we still succeeded.
                    return dy >= -0.05 && dx <= 1.2 && dz <= 1.2;
                case DROP:
                    // Drop Completion Zone:
                    // Vertical: Must be at or below the target Y + 0.5 (waist height).
                    // Horizontal: Must be within 0.8 blocks (tighter, we want to land ON the block).
                    return dy <= 0.5 && dx <= 0.8 && dz <= 0.8;
                case SWIM:
                    // Swim Completion Zone:
                    // 3D Radius check.
                    return agentPos.distanceSquared(targetPos) < 1.5 * 1.5;
                case WALK:
                default:
                    // Walk segments are continuous and handled by the PathFollower's distance projection.
                    // They don't have a "Completion Zone" in this context because they aren't discrete maneuvers.
                    return false;
            }
        }
    }

    // Compact constructor for standard walking (backward compatibility)
    public SteeringResult(Location target, MovementType movementType) {
        this(target, new Vector(0, 0, 0), movementType);
    }

    // Constructor for swimming/3D movement
    public SteeringResult(Location target, MovementType movementType, Vector desiredVelocity) {
        this(target, desiredVelocity, movementType);
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