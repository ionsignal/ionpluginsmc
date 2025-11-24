package com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.Maneuver;

import net.minecraft.world.entity.ai.attributes.Attributes;

import org.bukkit.Location;

import java.util.Optional;

/**
 * Handles the state machine for a controlled drop (Walk off ledge -> Fall -> Land).
 * Unlike JumpManeuver, this does NOT apply vertical impulse.
 */
public class DropManeuver implements Maneuver {
    private final Location targetWaypoint;
    private DropState state;
    private int ticks;

    private enum DropState {
        APPROACHING_EDGE, // Moving horizontally towards the drop point
        FALLING, // Airborne, waiting for ground contact
        LANDED, // Successfully reached target Y level on ground
        FAILED // Timeout or stuck
    }

    public DropManeuver(Location targetWaypoint) {
        this.targetWaypoint = targetWaypoint;
        this.state = DropState.APPROACHING_EDGE;
    }

    @Override
    public void start(PersonaEntity entity) {
        ticks = 0;
    }

    @Override
    @SuppressWarnings("null")
    public void tick(PersonaEntity entity) {
        ticks++;
        // We must continuously feed the MoveControl to walk off the ledge.
        // We use standard movement speed.
        double speed = entity.getAttributeValue(Attributes.MOVEMENT_SPEED);
        entity.getMoveControl().setWantedPosition(
                targetWaypoint.getX(),
                targetWaypoint.getY(),
                targetWaypoint.getZ(),
                speed);
        switch (state) {
            case APPROACHING_EDGE -> {
                // If we are no longer on ground, we have walked off the edge
                if (!entity.onGround()) {
                    state = DropState.FALLING;
                    ticks = 0; // Reset ticks for falling phase
                } else if (ticks > 20) {
                    // If we haven't left the ground in 1 second, we might be stuck or blocked
                    state = DropState.FAILED;
                }
            }
            case FALLING -> {
                // Wait for ground contact
                if (entity.onGround()) {
                    state = DropState.LANDED;
                } else if (ticks > 40) {
                    // Falling too long (safety timeout)
                    state = DropState.FAILED;
                }
            }
            case LANDED, FAILED -> {
                // Terminal states, do nothing
            }
        }
    }

    @Override
    public boolean isFinished() {
        return state == DropState.LANDED || state == DropState.FAILED;
    }

    @Override
    public Optional<Location> getOrientationTarget() {
        return Optional.of(targetWaypoint);
    }

    @Override
    public void stop(PersonaEntity entity) {
        // no-op
    }
}