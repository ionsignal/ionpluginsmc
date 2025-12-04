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
 * Handles the state machine for a standard jump implementing "Coasting"
 * physics and "Snap-on-Land" visuals.
 */
public class JumpManeuver implements Maneuver {
    private static final double JUMP_BOOST_SPEED = 1.3;
    private static final int HARD_TIMEOUT = 60;

    private final Location targetWaypoint;
    private final double startY;

    private Location lookTarget;
    private JumpState state;
    private int ticks;
    private Optional<Vector> exitHeading = Optional.empty();

    private enum JumpState {
        PREPARING, JUMPING, ASCENDING, DESCENDING, LANDED, FAILED
    }

    public JumpManeuver(Location targetWaypoint, double startY) {
        this.targetWaypoint = targetWaypoint;
        this.startY = startY;
        this.state = JumpState.PREPARING;
    }

    @Override
    public void start(PersonaEntity entity, Optional<Vector> exitHeading) {
        this.ticks = 0;
        this.exitHeading = exitHeading;
        Vector direction = targetWaypoint.toVector()
                .subtract(entity.getBukkitEntity().getLocation().toVector());
        direction.setY(0).normalize();
        if (direction.lengthSquared() < 0.01) {
            direction = entity.getBukkitEntity().getLocation().getDirection().setY(0).normalize();
        }
        this.lookTarget = targetWaypoint.clone().add(0, 1.0, 0).add(direction);
    }

    @Override
    public ManeuverResult stop(PersonaEntity entity) {
        entity.getJumpControl().stop();
        if (state == JumpState.LANDED) {
            return new ManeuverResult(ManeuverResult.Status.SUCCESS, entity.getBukkitEntity().getLocation(), "Jump landed");
        }
        return new ManeuverResult(ManeuverResult.Status.FAILED, entity.getBukkitEntity().getLocation(), "Jump failed");
    }

    @Override
    public void tick(PersonaEntity entity) {
        ticks++;
        if (ticks > HARD_TIMEOUT) {
            state = JumpState.FAILED;
            return;
        }
        // Physics: Coasting
        if (state != JumpState.LANDED && state != JumpState.FAILED) {
            entity.getMoveControl().setWantedPosition(
                    targetWaypoint.getX(),
                    targetWaypoint.getY(),
                    targetWaypoint.getZ(),
                    JUMP_BOOST_SPEED);
        }
        switch (state) {
            case PREPARING -> {
                if (entity.onGround()) {
                    entity.getJumpControl().jump();
                    state = JumpState.JUMPING;
                    ticks = 0;
                } else if (ticks > 10) {
                    state = JumpState.FAILED;
                }
            }
            case JUMPING -> {
                entity.getJumpControl().jump();
                if (entity.getY() > startY + 0.1) {
                    state = JumpState.ASCENDING;
                    ticks = 0;
                } else if (ticks > 10) {
                    state = JumpState.FAILED;
                }
            }
            case ASCENDING -> {
                entity.getJumpControl().stop();
                if (entity.getDeltaMovement().y < 0) {
                    state = JumpState.DESCENDING;
                    ticks = 0;
                }
            }
            case DESCENDING -> {
                if (entity.onGround()) {
                    state = JumpState.LANDED;
                }
            }
            case LANDED, FAILED -> {
            }
        }
    }

    @Override
    public boolean isFinished() {
        return state == JumpState.LANDED || state == JumpState.FAILED;
    }

    @Override
    public OrientationIntent getOrientation() {
        // Snap-on-Land Logic:
        // If we just landed and have an exit heading, snap to it immediately.
        if (state == JumpState.LANDED && exitHeading.isPresent()) {
            return new OrientationIntent.AlignToHeading(exitHeading.get(), true, BodyMode.LOCKED);
        }
        // Otherwise, look at the jump target with body locked to head (aggressive jumping posture)
        return new OrientationIntent.FocusOnLocation(lookTarget, BodyMode.LOCKED);
    }
}