package com.ionsignal.minecraft.ionnerrus.persona.locomotion;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.DropManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers.JumpManeuver;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The tactical layer of movement control that receives high-level intents from the Navigator and
 * arbitrates between standard movement, maneuvers, and repulsion vectors to avoid and traverse
 * obstacles effectively.
 */
public class LocomotionController {
    private final PersonaEntity entity;
    private boolean blocked = false;

    @Nullable
    private Maneuver currentManeuver;

    // Stores the intent received from Navigator for this tick
    @Nullable
    private SteeringResult currentIntent;
    private float currentSpeed;

    // Tuning for Repulsion
    private static final double WHISKER_LENGTH = 0.75;
    private static final double SHOULDER_WIDTH = 0.35; // Slightly wider than actual hitbox (0.3)
    private static final double REPULSION_STRENGTH = 0.6; // How strongly to push away

    // Reusable vector for UP to save allocation
    private static final Vector UP_VECTOR = new Vector(0, 1, 0);

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
        // Maneuver Priority
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
        // Standard Movement Processing
        if (currentIntent != null) {
            switch (currentIntent.movementType()) {
                case JUMP -> startManeuver(new JumpManeuver(currentIntent.target(), entity.getY()));
                case DROP -> startManeuver(new DropManeuver(currentIntent.target()));
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
        Location currentLoc = entity.getBukkitEntity().getLocation();
        Vector intentVector = intent.target().toVector().subtract(currentLoc.toVector());
        // Normalize intent for calculation
        if (intentVector.lengthSquared() > 0.001) {
            intentVector.normalize();
        }
        // Calculate Repulsion
        Vector repulsion = calculateRepulsionVector(currentLoc, intentVector);
        // Check for Dead End (NaN flag)
        if (Double.isNaN(repulsion.getX())) {
            entity.getMoveControl().stop();
            // Flag collision for the Navigator
            this.blocked = true;
            return;
        }
        // Apply Repulsion: Virtual Target = Intent + Repulsion
        Vector adjustedDirection = intentVector.clone().add(repulsion);
        // Safe normalization
        if (adjustedDirection.lengthSquared() > 0.001) {
            adjustedDirection.normalize();
        }
        // Project Virtual Target 2.0 blocks out to ensure smooth NMS movement
        Location virtualTarget = currentLoc.clone().add(adjustedDirection.multiply(2.0));
        entity.getMoveControl().setWantedPosition(
                virtualTarget.getX(),
                virtualTarget.getY(),
                virtualTarget.getZ(),
                speed);
    }

    private Vector calculateRepulsionVector(Location currentLoc, Vector forwardDir) {
        if (forwardDir.lengthSquared() < 0.001) {
            return new Vector(0, 0, 0);
        }
        // Use +0.35 to ensure we hit slabs/beds but stay above carpets
        double startY = currentLoc.getY() + 0.35;
        Vec3 origin = new Vec3(currentLoc.getX(), startY, currentLoc.getZ());
        // Forward x Up = Right (Right-Hand Rule)
        Vector rightDir = forwardDir.getCrossProduct(UP_VECTOR).normalize();
        // Define Whiskers
        Vec3 centerStart = origin;
        // Left is -Right
        Vec3 leftStart = origin.add(rightDir.getX() * -SHOULDER_WIDTH, 0, rightDir.getZ() * -SHOULDER_WIDTH);
        // Right is +Right
        Vec3 rightStart = origin.add(rightDir.getX() * SHOULDER_WIDTH, 0, rightDir.getZ() * SHOULDER_WIDTH);
        boolean hitCenter = castWhisker(centerStart, forwardDir);
        boolean hitLeft = castWhisker(leftStart, forwardDir);
        boolean hitRight = castWhisker(rightStart, forwardDir);
        // Logic Matrix
        if (!hitLeft && !hitCenter && !hitRight) {
            return new Vector(0, 0, 0);
        }
        // Dead End: All blocked
        if (hitLeft && hitCenter && hitRight) {
            return new Vector(Double.NaN, Double.NaN, Double.NaN);
        }
        Vector repulsion = new Vector(0, 0, 0);
        if (hitLeft) {
            // Hit Left -> Push Right
            repulsion.add(rightDir.clone().multiply(REPULSION_STRENGTH));
        }
        if (hitRight) {
            // Hit Right -> Push Left (Negative Right)
            repulsion.add(rightDir.clone().multiply(-REPULSION_STRENGTH));
        }
        if (hitCenter) {
            // Hit Center Only (Pole/Post) -> Bias Right to deflect
            if (!hitLeft && !hitRight) {
                repulsion.add(rightDir.clone().multiply(REPULSION_STRENGTH));
            }
        }
        return repulsion;
    }

    private boolean castWhisker(Vec3 start, Vector direction) {
        Vec3 end = start.add(direction.getX() * WHISKER_LENGTH, 0, direction.getZ() * WHISKER_LENGTH);
        BlockHitResult result = NavigationHelper.rayTrace(entity.level(), start, end);
        return result.getType() != HitResult.Type.MISS;
    }

    private void handleSwim(SteeringResult intent, float speed) {
        // Apply forward momentum for normal in-water swimming
        entity.getMoveControl().setWantedPosition(
                intent.target().getX(),
                intent.target().getY(),
                intent.target().getZ(),
                speed * 0.7f // Swim speed penalty
        );
        // PHASE 4 UPDATE: Use 3D vector for vertical control
        // We check the Y component of the desired velocity vector
        double verticalVelocity = intent.desiredVelocity().getY();
        if (verticalVelocity > 0.1) {
            // Swimming Up
            entity.getJumpControl().jump();
            entity.setShiftKeyDown(false);
        } else if (verticalVelocity < -0.1) {
            // Swimming Down (Sneak causes sinking in water)
            entity.getJumpControl().stop();
            entity.setShiftKeyDown(true);
        } else {
            // Neutral buoyancy / Level swimming
            entity.getJumpControl().stop();
            entity.setShiftKeyDown(false);
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

    // Add explicit clear method
    public void clearBlocked() {
        this.blocked = false;
    }

    /**
     * Checks if the immediate path towards the target is blocked by a solid obstacle.
     * Uses the unified NavigationHelper against the LIVE server level.
     */
    public boolean isBlocked() {
        return blocked;
    }

    /**
     * Checks if a complex maneuver is currently executing: this is used by Navigator to pause stuck
     * detection during jumps/drops and other maneuvers.
     */
    public boolean isManeuvering() {
        return currentManeuver != null;
    }

    /**
     * Checks if the active maneuver requires the body to be locked to the look target.
     */
    public boolean shouldLockBodyRotation() {
        return currentManeuver != null && currentManeuver.shouldLockBody();
    }
}