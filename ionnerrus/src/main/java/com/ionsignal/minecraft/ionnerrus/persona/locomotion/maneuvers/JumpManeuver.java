package com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.Maneuver;

import net.minecraft.world.entity.ai.attributes.Attributes;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Optional;

/**
 * Handles the state machine for a standard jump (Prepare -> Jump -> Ascend -> Descend -> Land).
 */
public class JumpManeuver implements Maneuver {
    private final Location targetWaypoint;
    private final double startY;
    private Location lookTarget;
    private JumpState state;
    private int ticks;

    private enum JumpState {
        PREPARING, JUMPING, ASCENDING, DESCENDING, LANDED, FAILED
    }

    public JumpManeuver(Location targetWaypoint, double startY) {
        this.targetWaypoint = targetWaypoint;
        this.startY = startY;
        this.state = JumpState.PREPARING;
    }

    @Override
    public void start(PersonaEntity entity) {
        ticks = 0;
        // Calculate a visual target that looks "through" the jump
        // 1 block up (eye level relative to landing) and 1 block forward
        Vector direction = targetWaypoint.toVector()
                .subtract(entity.getBukkitEntity().getLocation().toVector());
        direction.setY(0).normalize();
        // Handle vertical-only jumps by defaulting to current facing if direction is zero
        if (direction.lengthSquared() < 0.01) {
            direction = entity.getBukkitEntity().getLocation().getDirection().setY(0).normalize();
        }
        this.lookTarget = targetWaypoint.clone().add(0, 1.0, 0).add(direction);
    }

    @Override
    @SuppressWarnings("null")
    public void tick(PersonaEntity entity) {
        ticks++;
        // 1. Forward Momentum: CRITICAL
        // We must continuously feed the MoveControl to keep moving through the air.
        // NMS resets zza/xxa every tick if not set.
        double speed = entity.getAttributeValue(Attributes.MOVEMENT_SPEED) * 1.3; // Slight boost for air control
        entity.getMoveControl().setWantedPosition(
                targetWaypoint.getX(),
                targetWaypoint.getY(),
                targetWaypoint.getZ(),
                speed);
        // 2. State Machine
        switch (state) {
            case PREPARING -> {
                if (entity.onGround()) {
                    entity.getJumpControl().jump(); // NMS: setJumping(true)
                    state = JumpState.JUMPING;
                    ticks = 0; // Reset ticks for next phase
                } else if (ticks > 10) {
                    state = JumpState.FAILED;
                }
            }
            case JUMPING -> {
                entity.getJumpControl().jump(); // Keep holding jump for fluid dynamics
                // Check if we have left the ground significantly
                if (entity.getY() > startY + 0.1) {
                    state = JumpState.ASCENDING;
                    ticks = 0;
                } else if (ticks > 10) {
                    state = JumpState.FAILED; // Failed to lift off
                }
            }
            case ASCENDING -> {
                // Stop holding jump key to allow gravity to start arc naturally
                entity.getJumpControl().stop();
                // Detect apex of jump (vertical velocity becomes negative)
                if (entity.getDeltaMovement().y < 0) {
                    state = JumpState.DESCENDING;
                    ticks = 0;
                } else if (ticks > 20) {
                    state = JumpState.FAILED; // Stuck ascending?
                }
            }
            case DESCENDING -> {
                if (entity.onGround()) {
                    state = JumpState.LANDED;
                } else if (ticks > 40) {
                    state = JumpState.FAILED; // Falling too long
                }
            }
            case LANDED, FAILED -> {
                // Terminal states, do nothing
            }
        }
    }

    @Override
    public boolean isFinished() {
        return state == JumpState.LANDED || state == JumpState.FAILED;
    }

    @Override
    public Optional<Location> getOrientationTarget() {
        // Force the entity to look at the landing spot during the jump
        return Optional.ofNullable(lookTarget);
    }

    @Override
    public void stop(PersonaEntity entity) {
        entity.getJumpControl().stop();
        // We don't stop move control here, as we might transition immediately to walking
    }
}