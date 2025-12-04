package com.ionsignal.minecraft.ionnerrus.persona.locomotion;

import org.bukkit.Location;

/**
 * Encapsulates the outcome of a complex physical maneuver for explicit handoff between
 * LocomotionController and Navigator.
 */
public record ManeuverResult(
        Status status,
        Location finalPosition,
        String message) {

    public enum Status {
        SUCCESS, FAILED, CANCELLED
    }
}