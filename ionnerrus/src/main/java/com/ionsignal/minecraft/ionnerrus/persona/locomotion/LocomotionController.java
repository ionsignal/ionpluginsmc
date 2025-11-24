package com.ionsignal.minecraft.ionnerrus.persona.locomotion;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.DropManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.JumpManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The tactical layer of movement. Receives high-level intents (SteeringResult) from
 * the Navigator and arbitrates between standard movement and complex Maneuvers.
 */
public class LocomotionController {

    private final PersonaEntity entity;

    @Nullable
    private Maneuver currentManeuver;

    // Stores the intent received from Navigator for this tick
    @Nullable
    private SteeringResult currentIntent;
    private float currentSpeed;

    public LocomotionController(PersonaEntity entity) {
        this.entity = entity;
    }

    /**
     * Receives a steering intent from the Navigator.
     * This does NOT execute movement immediately; it sets the state for the tick() cycle.
     *
     * @param intent
     *            The calculated steering target and type (Walk, Jump, Swim).
     * @param speed
     *            The desired movement speed.
     */
    public void drive(SteeringResult intent, float speed) {
        this.currentIntent = intent;
        this.currentSpeed = speed;
    }

    /**
     * Main tick loop. Arbitrates between active maneuvers and new intents.
     * 1. If Maneuver is active -> Tick it. Ignore intent (unless intent is null/stop).
     * 2. If Maneuver is done -> Stop it, clear it.
     * 3. If No Maneuver -> Process currentIntent (Start new maneuver or Apply standard movement).
     */
    public void tick() {
        // 1. Maneuver Priority
        if (currentManeuver != null) {
            currentManeuver.tick(entity);
            if (currentManeuver.isFinished()) {
                currentManeuver.stop(entity);
                currentManeuver = null;
            }
            // While maneuvering, we ignore the Navigator's standard intent for this tick
            // But we must consume it to prevent stale data
            currentIntent = null;
            return;
        }

        // 2. Standard Movement Processing
        if (currentIntent != null) {
            switch (currentIntent.movementType()) {
                case JUMP -> startManeuver(new JumpManeuver(currentIntent.target(), entity.getY())); // Split JUMP
                case DROP -> startManeuver(new DropManeuver(currentIntent.target())); // Added DROP handler
                case SWIM -> handleSwim(currentIntent, currentSpeed);
                case WALK -> handleStandardMovement(currentIntent, currentSpeed);
            }
            currentIntent = null; // Consumed
        } else {
            // Failsafe: No intent received this tick -> Stop moving
            // This handles the case where Navigator stops ticking or crashes
            stop();
        }
    }

    /**
     * Stops all movement and cancels active maneuvers.
     * Must be called when Navigator resets or cancels a task.
     */
    public void stop() {
        if (currentManeuver != null) {
            currentManeuver.stop(entity);
            currentManeuver = null;
        }
        entity.getMoveControl().stop();
        entity.getJumpControl().stop();
        entity.setShiftKeyDown(false);
        entity.setSprinting(false);
    }

    /**
     * Handles standard ground movement based on the intent.
     */
    private void handleStandardMovement(SteeringResult intent, float speed) {
        // Ensure jump/shift are off for normal walking
        entity.getJumpControl().stop();
        entity.setShiftKeyDown(false);

        entity.getMoveControl().setWantedPosition(
                intent.target().getX(),
                intent.target().getY(),
                intent.target().getZ(),
                speed);
    }

    private void handleSwim(SteeringResult intent, float speed) {
        // Apply forward momentum for normal in-water swimming
        entity.getMoveControl().setWantedPosition(
                intent.target().getX(),
                intent.target().getY(),
                intent.target().getZ(),
                speed * 0.7f // Swim speed penalty
        );

        // Handle vertical swimming (Up/Down)
        switch (intent.verticalDirection()) {
            case UP -> {
                entity.getJumpControl().jump();
                entity.setShiftKeyDown(false);
            }
            case DOWN -> {
                entity.getJumpControl().stop();
                entity.setShiftKeyDown(true); // Sneak to sink
            }
            case NONE -> {
                entity.getJumpControl().stop();
                entity.setShiftKeyDown(false);
            }
        }
    }

    private void startManeuver(Maneuver maneuver) {
        if (currentManeuver != null) {
            currentManeuver.stop(entity);
        }
        currentManeuver = maneuver;
        currentManeuver.start(entity);
    }

    /**
     * Checks if the active maneuver requires a specific orientation.
     */
    public Optional<Location> getOrientationOverride() {
        if (currentManeuver != null) {
            return currentManeuver.getOrientationTarget();
        }
        return Optional.empty();
    }
}