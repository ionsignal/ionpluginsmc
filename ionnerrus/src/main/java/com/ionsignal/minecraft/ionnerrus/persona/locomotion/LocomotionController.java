package com.ionsignal.minecraft.ionnerrus.persona.locomotion;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.DropManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.JumpManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.WaterExitManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl.BodyMode;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult;
import com.ionsignal.minecraft.ionnerrus.persona.orientation.OrientationIntent;

import net.minecraft.tags.FluidTags;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Optional;

/**
 * The tactical layer of movement control that receives high-level intents from the Navigator and
 * arbitrates between standard movement, maneuvers, and repulsion vectors to avoid and traverse
 * obstacles effectively.
 */
public class LocomotionController {
    private final PersonaEntity entity;

    private Maneuver currentManeuver;
    private SteeringResult currentIntent;
    private float currentSpeed;
    private ManeuverResult lastResult;

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
        this.lastResult = null;
    }

    /**
     * Main tick loop. Arbitrates between active maneuvers and new intents.
     * 1. If Maneuver is active -> Tick it. Ignore intent (unless intent is null/stop).
     * 2. If Maneuver is done -> Stop it, clear it.
     * 3. If No Maneuver -> Process currentIntent (Start new maneuver or Apply standard movement).
     */
    public void tick() {
        // Maneuver Priority
        if (currentManeuver != null) {
            currentManeuver.tick(entity);
            if (currentManeuver.isFinished()) {
                this.lastResult = currentManeuver.stop(entity);
                currentManeuver = null;
            }
            // While maneuvering, we ignore the Navigator's standard intent for this tick
            // But we must consume it to prevent stale data
            currentIntent = null;
            return;
        }
        // Standard Movement Processing
        if (currentIntent != null) {
            switch (currentIntent.movementType()) {
                case JUMP -> startManeuver(new JumpManeuver(currentIntent.target(), entity.getY()));
                case DROP -> startManeuver(new DropManeuver(currentIntent.target()));
                case WATER_EXIT -> startManeuver(new WaterExitManeuver(currentIntent.target())); // Added case
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
        // Reset Logic
        entity.getMoveControl().stop();
        entity.getJumpControl().stop();
        entity.setShiftKeyDown(false);
        entity.setSprinting(false);
        // Cancel Maneuvers
        if (currentManeuver != null) {
            currentManeuver.stop(entity);
            currentManeuver = null;
        }
        this.lastResult = null;
    }

    /**
     * Consumes the last maneuver result in a single atomic operation.
     */
    public Optional<ManeuverResult> popLastResult() {
        ManeuverResult res = this.lastResult;
        this.lastResult = null;
        return Optional.ofNullable(res);
    }

    /**
     * Handles standard ground movement based on the intent and clamps wantedY to prevent burrowing into
     * carpets/stairs.
     */
    private void handleStandardMovement(SteeringResult intent, float speed) {
        entity.getJumpControl().stop();
        entity.setShiftKeyDown(false);
        entity.setSprinting(false);
        // When walking down a slope (e.g., Slab -> Floor), the target Y is below us.
        // We clamp wantedY to our current Y to drive HORIZONTALLY off the ledge.
        // We let the physics engine handle the vertical drop via gravity.
        // This prevents "driving into the floor" friction.
        Location target = intent.target();
        double wantedY = target.getY();
        if (!entity.onClimbable() && !entity.isInWater()) {
            if (wantedY < entity.getY()) {
                wantedY = entity.getY();
            }
        }
        entity.getMoveControl().setWantedPosition(
                target.getX(),
                wantedY,
                target.getZ(),
                speed);
    }

    @SuppressWarnings("null")
    private void handleSwim(SteeringResult intent, float speed) {
        // Pitch-Based "Dolphin" Swimming
        // Apply Forward Momentum (Throttle)
        entity.getMoveControl().setWantedPosition(
                intent.target().getX(),
                intent.target().getY(),
                intent.target().getZ(),
                speed);
        // Handle State & Verticality
        boolean isSubmerged = entity.isEyeInFluid(FluidTags.WATER);
        if (isSubmerged) {
            // Underwater: Use Pitch to steer.
            // Disable discrete vertical inputs.
            entity.getJumpControl().stop();
            entity.setShiftKeyDown(false);
            // Sprinting is required for the "swimming" pose and speed
            entity.setSprinting(true);
        } else {
            // Surface: Prevent bobbing.
            // If target is significantly higher, Jump to breach.
            double verticalDiff = intent.target().getY() - entity.getY();
            if (verticalDiff > 0.5) {
                entity.getJumpControl().jump();
            } else {
                entity.getJumpControl().stop();
            }
            // Don't sprint at surface to avoid "porpoising" out of water uncontrollably
            entity.setSprinting(false);
        }
    }

    private void startManeuver(Maneuver maneuver) {
        if (currentManeuver != null) {
            currentManeuver.stop(entity);
        }
        currentManeuver = maneuver;
        currentManeuver.start(entity, currentIntent != null ? currentIntent.exitHeading() : Optional.empty());
    }

    /**
     * Checks if the active maneuver requires a specific orientation intent.
     * Replaces the old getOrientationOverride() and shouldLockBodyRotation() methods.
     */
    public Optional<OrientationIntent> getOrientationIntent() {
        if (currentManeuver != null) {
            return Optional.of(currentManeuver.getOrientation());
        }
        // To swim effectively (Dolphin style), the body must align with the velocity vector.
        if (currentIntent != null && currentIntent.movementType() == SteeringResult.MovementType.SWIM) {
            Vector dir = currentIntent.target().toVector()
                    .subtract(entity.getBukkitEntity().getLocation().toVector())
                    .normalize();
            // Snap = false, Mode = LOCKED (Body follows head)
            return Optional.of(new OrientationIntent.AlignToHeading(dir, false, BodyMode.LOCKED));
        }
        return Optional.empty();
    }

    /**
     * Checks if a complex maneuver is currently executing: this is used by Navigator to pause stuck
     * detection during jumps/drops and other maneuvers.
     */
    public boolean isManeuvering() {
        return currentManeuver != null;
    }
}