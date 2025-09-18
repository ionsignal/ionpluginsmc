package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import org.bukkit.Location;

public record SteeringResult(Location target, MovementType movementType, VerticalDirection verticalDirection) {
    public enum MovementType {
        WALK, JUMP, DROP, SWIM
    }

    public enum VerticalDirection {
        UP, DOWN, NONE
    }

    public SteeringResult(Location target, MovementType movementType) {
        this(target, movementType, VerticalDirection.NONE);
    }
}