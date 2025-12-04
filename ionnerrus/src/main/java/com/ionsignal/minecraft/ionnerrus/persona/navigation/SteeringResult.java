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
            double dy = agentPos.getY() - targetPos.getY();
            switch (this) {
                case JUMP:
                    return dy >= -0.05 && dx <= 1.2 && dz <= 1.2;
                case DROP:
                    return dx <= 0.94 && dz <= 0.94;
                case SWIM:
                    return agentPos.distanceSquared(targetPos) < 1.5 * 1.5;
                case WALK:
                default:
                    return false;
            }
        }
    }

    public SteeringResult(Location target, MovementType movementType) {
        this(target, movementType, Optional.empty());
    }
}