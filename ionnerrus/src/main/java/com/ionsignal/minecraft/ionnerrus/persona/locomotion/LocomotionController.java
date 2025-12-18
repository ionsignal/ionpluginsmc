package com.ionsignal.minecraft.ionnerrus.persona.locomotion;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.DropManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.JumpManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.WaterExitManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl.BodyMode;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult;
import com.ionsignal.minecraft.ionnerrus.persona.orientation.OrientationIntent;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.control.MoveControl.Operation;

import org.bukkit.Location;

import java.util.Optional;

/**
 * The tactical layer of movement control that receives high-level intents from the Navigator and
 * arbitrates between standard movement, maneuvers, and repulsion vectors to avoid and traverse
 * obstacles effectively.
 */
public class LocomotionController {
    private final PersonaEntity entity;

    private float currentSpeed;
    private Maneuver currentManeuver;
    private ManeuverResult lastResult;
    private SteeringResult currentIntent;

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
                case SWIM -> handleSwim(currentIntent, currentSpeed);
                case WALK -> handleStandardMovement(currentIntent, currentSpeed);
                case WADE -> handleStandardMovement(currentIntent, currentSpeed); // Wading uses walking physics
                case JUMP -> startManeuver(new JumpManeuver(currentIntent.target()));
                case DROP -> startManeuver(new DropManeuver(currentIntent.target()));
                case WATER_EXIT -> startManeuver(new WaterExitManeuver(currentIntent.target()));
            }
            currentIntent = null; // Consumed
        } else {
            // Failsafe: No intent received this tick -> Stop moving
            // This handles the case where Navigator stops ticking or crashes
            if (entity.getMoveControl().getOperation() != Operation.WAIT) {
                stop();
            }
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
        entity.setSwimming(false);
        // Cancel Maneuvers
        if (currentManeuver != null) {
            currentManeuver.stop(entity);
            currentManeuver = null;
        }
        this.currentSpeed = 0.0f;
        this.lastResult = null;
        this.currentIntent = null;
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
        entity.setSwimming(false); // Ensure swim state is cleared
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
        Location target = intent.target();
        // Sprinting activates the swimming pose and speed boost
        entity.setSprinting(true);
        entity.setSwimming(true); // Explicit hitbox reduction
        // If grounded, force a jump to detach from the floor and enter 3D swim mode.
        if (entity.onGround() && entity.level().getFluidState(entity.blockPosition().above(2)).is(FluidTags.WATER)) {
            entity.getJumpControl().jump();
        } else {
            entity.getJumpControl().stop(); // Disable bobbing
        }
        // Apply Throttle (MoveControl handles the 3D vector if in water)
        entity.getMoveControl().setWantedPosition(
                target.getX(),
                target.getY(),
                target.getZ(),
                speed);
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
        // Deep Swimming (Sprinting + Water) is a physics-driven state where
        // PersonaMoveControl takes full control of rotation (Torpedo mode).
        if (entity.isInWater() && entity.isSprinting()) {
            return Optional.of(new OrientationIntent.AlignToHeading(
                    entity.getBukkitEntity().getLocation().getDirection(), // Vector is ignored by MoveControl
                    true,
                    BodyMode.EXTERNAL));
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