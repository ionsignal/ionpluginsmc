package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import org.bukkit.Location;

public record SteeringResult(Location target, MovementType movementType) {
    public enum MovementType {
        WALK, JUMP
    }
}