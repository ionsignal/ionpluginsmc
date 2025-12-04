package com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.Maneuver;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.ManeuverResult;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl.BodyMode;
import com.ionsignal.minecraft.ionnerrus.persona.orientation.OrientationIntent;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Optional;

/**
 * Handles the state machine for a controlled drop implementing a "Look-while-falling" effect.
 */
public class DropManeuver implements Maneuver {
    private static final double DROP_APPROACH_SPEED = 0.8;
    private static final int HARD_TIMEOUT = 60;

    private final Location targetWaypoint;

    private DropState state;
    private int ticks;
    // private Optional<Vector> exitHeading = Optional.empty();

    private enum DropState {
        APPROACHING_EDGE, FALLING, LANDED, FAILED
    }

    public DropManeuver(Location targetWaypoint) {
        this.targetWaypoint = targetWaypoint;
        this.state = DropState.APPROACHING_EDGE;
    }

    @Override
    public void start(PersonaEntity entity, Optional<Vector> exitHeading) {
        this.ticks = 0;
        // this.exitHeading = exitHeading;
        entity.setShiftKeyDown(false);
    }

    @Override
    public ManeuverResult stop(PersonaEntity entity) {
        if (state == DropState.LANDED) {
            return new ManeuverResult(ManeuverResult.Status.SUCCESS, entity.getBukkitEntity().getLocation(), "Landed safely");
        }
        return new ManeuverResult(ManeuverResult.Status.FAILED, entity.getBukkitEntity().getLocation(), "Drop failed or timed out");
    }

    @Override
    public void tick(PersonaEntity entity) {
        ticks++;
        if (entity.isShiftKeyDown()) {
            entity.setShiftKeyDown(false);
        }
        if (ticks > HARD_TIMEOUT) {
            state = DropState.FAILED;
            return;
        }
        switch (state) {
            case APPROACHING_EDGE -> {
                // Drive towards the edge
                entity.getMoveControl().setWantedPosition(
                        targetWaypoint.getX(),
                        targetWaypoint.getY(),
                        targetWaypoint.getZ(),
                        DROP_APPROACH_SPEED);
                if (!entity.onGround()) {
                    entity.getMoveControl().stop();
                    state = DropState.FALLING;
                    ticks = 0;
                } else if (ticks > 20) {
                    state = DropState.FAILED;
                }
            }
            case FALLING -> {
                // Physics: Coast (Gravity only). Do NOT set wanted position.
                // Rotation is handled via getOrientation() returning AlignToHeading.
                if (entity.onGround()) {
                    state = DropState.LANDED;
                }
            }
            case LANDED, FAILED -> {
                // Terminal states
            }
        }
    }

    @Override
    public boolean isFinished() {
        return state == DropState.LANDED || state == DropState.FAILED;
    }

    @Override
    public OrientationIntent getOrientation() {
        if (state == DropState.APPROACHING_EDGE) {
            // While approaching, look at the drop point naturally.
            return new OrientationIntent.FocusOnLocation(targetWaypoint, BodyMode.FREE);
        }
        return new OrientationIntent.Idle();
    }

    /*
     * @Override
     * public OrientationIntent getOrientation() {
     * // While falling, we want to snap the body to the exit heading to prepare for the next move.
     * if (state == DropState.FALLING && exitHeading.isPresent()) {
     * Vector heading = exitHeading.get();
     * // If heading is purely vertical, preserve current yaw (Idle) to prevent snapping to South (0,0).
     * if ((heading.getX() * heading.getX() + heading.getZ() * heading.getZ()) < 0.01) {
     * return new OrientationIntent.Idle();
     * }
     * // Snap = true (Instant turn), Mode = LOCKED (Body follows head)
     * return new OrientationIntent.AlignToHeading(exitHeading.get(), true, BodyMode.LOCKED);
     * }
     * // While approaching, look at the drop point naturally.
     * return new OrientationIntent.FocusOnLocation(targetWaypoint, BodyMode.FREE);
     * }
     */
}