package com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.Maneuver;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.ManeuverResult;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl.BodyMode;
import com.ionsignal.minecraft.ionnerrus.persona.orientation.OrientationIntent;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Optional;

/**
 * Handles the state machine for a controlled drop implementing a "Look-while-falling" effect.
 */
public class DropManeuver implements Maneuver {
    private static final boolean DEBUG_VISUALIZATION = true;
    private static final double DROP_APPROACH_SPEED = 0.8;
    private static final int HARD_TIMEOUT = 60;

    private final Location targetWaypoint;

    private DropState state;
    private int ticks;
    private Optional<Vector> exitHeading = Optional.empty();

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
        this.exitHeading = exitHeading;
        entity.setShiftKeyDown(false);
        if (DEBUG_VISUALIZATION) {
            Location visual = targetWaypoint.clone();
            DebugVisualizer.highlightBlock(visual.subtract(0.5, 0, 0.5), 40, NamedTextColor.DARK_GRAY);
        }
    }

    @Override
    public ManeuverResult stop(PersonaEntity entity) {
        if (state == DropState.LANDED) {
            return new ManeuverResult(
                    ManeuverResult.Status.SUCCESS,
                    entity.getBukkitEntity().getLocation(), "Landed safely");
        }
        return new ManeuverResult(
                ManeuverResult.Status.FAILED,
                entity.getBukkitEntity().getLocation(), "Drop failed or timed out");
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
                // Calculate overshoot vector to force edge traversal (Edge Drive)
                Vector driveDir = calculateDriveVector(entity);
                double wantedX, wantedZ;
                if (driveDir.lengthSquared() > 0.001) {
                    // Drive towards a virtual point 2 blocks ahead to ensure we walk OFF the ledge
                    wantedX = entity.getX() + (driveDir.getX() * 2.0);
                    wantedZ = entity.getZ() + (driveDir.getZ() * 2.0);
                } else {
                    // Fallback: Target is directly below (Vertical Drop), just try to move to it
                    wantedX = targetWaypoint.getX();
                    wantedZ = targetWaypoint.getZ();
                }
                // Drive towards the edge/overshoot point
                entity.getMoveControl().setWantedPosition(
                        wantedX,
                        targetWaypoint.getY(),
                        wantedZ,
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
        // Approach Phase: Look at where we are stepping to ensure we clear the ledge
        if (state == DropState.APPROACHING_EDGE) {
            return new OrientationIntent.FocusOnLocation(targetWaypoint, BodyMode.FREE);
        }
        // Fall & Land Phase: Pre-align to the exit vector
        if (exitHeading.isPresent()) {
            // If we have landed, SNAP to the heading to ensure 100% accuracy for the next tick
            boolean snap = (state == DropState.LANDED);
            // While falling, BodyMode.LOCKED ensures the body rotates with the head
            // creating a "turning in mid-air" effect.
            return new OrientationIntent.AlignToHeading(exitHeading.get(), snap, BodyMode.LOCKED);
        }
        return new OrientationIntent.Idle();
    }

    // Helper to calculate the drive direction
    private Vector calculateDriveVector(PersonaEntity entity) {
        // Geometric direction to target
        Vector toTarget = targetWaypoint.toVector().subtract(entity.getBukkitEntity().getLocation().toVector());
        toTarget.setY(0); // Horizontal only
        // If we have a distinct horizontal direction, use it.
        if (toTarget.lengthSquared() > 0.001) {
            return toTarget.normalize();
        }
        // Zero vector (Vertical drop)
        return new Vector(0, 0, 0);
    }
}