package com.ionsignal.minecraft.ionnerrus.persona.locomotion;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;

import org.bukkit.Location;

import java.util.Optional;

/**
 * Represents a discrete, complex physical action that requires multi-tick execution and overrides
 * standard steering (e.g., Jumping, Climbing).
 */
public interface Maneuver {
    /**
     * Called once when the maneuver is first initiated by the LocomotionController.
     * Use this to set initial velocity or state (e.g., trigger the jump).
     */
    void start(PersonaEntity entity);

    /**
     * Called every tick to update the maneuver's state.
     * This handles the physics adjustments (e.g., applying forward momentum during a jump).
     */
    void tick(PersonaEntity entity);

    /**
     * Called when the maneuver is finished or interrupted to clean up state. (e.g., stopping jump
     * control input).
     */
    void stop(PersonaEntity entity);

    /**
     * Checks if the maneuver has completed (successfully or failed).
     * 
     * @return true if the maneuver is finished and control should return to the Navigator.
     */
    boolean isFinished();

    /**
     * Gets a specific location the entity *must* face during this maneuverand if present, this
     * overrides high-level orientation commands (like tracking an entity).
     *
     * @return An optional target location to look at.
     */
    default Optional<Location> getOrientationTarget() {
        return Optional.empty();
    }

    /**
     * Determines if the body rotation should be locked to the head rotation during this maneuver.
     * 
     * @return true to lock body (Jump), false to allow independent movement (Drop).
     */
    default boolean shouldLockBody() {
        return true; // Default to safe, locked behavior (e.g. for Jumps)
    }
}