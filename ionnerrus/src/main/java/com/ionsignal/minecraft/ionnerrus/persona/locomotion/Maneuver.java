package com.ionsignal.minecraft.ionnerrus.persona.locomotion;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.orientation.OrientationIntent;

import org.bukkit.util.Vector;

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
    void start(PersonaEntity entity, Optional<Vector> exitHeading);

    /**
     * Called every tick to update the maneuver's state.
     * This handles the physics adjustments (e.g., applying forward momentum during a jump).
     */
    void tick(PersonaEntity entity);

    /**
     * Called when the maneuver is finished or interrupted to clean up state. (e.g., stopping jump
     * control input).
     */
    ManeuverResult stop(PersonaEntity entity);

    /**
     * Checks if the maneuver has completed (successfully or failed).
     * 
     * @return true if the maneuver is finished and control should return to the Navigator.
     */
    boolean isFinished();

    /**
     * Gets the specific orientation intent required by this maneuver.
     * This replaces the ambiguous `getOrientationTarget` and `shouldLockBody` methods.
     *
     * @return The orientation intent for this tick.
     */
    default OrientationIntent getOrientation() {
        return new OrientationIntent.Idle();
    }
}