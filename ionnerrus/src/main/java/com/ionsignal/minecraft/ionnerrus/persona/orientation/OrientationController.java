package com.ionsignal.minecraft.ionnerrus.persona.orientation;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.LookResult;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;

/**
 * Manages the lifecycle of looking, facing, and tracking for a Persona.
 * Decouples high-level orientation logic from the physical bridge.
 */
public class OrientationController {
    private static final int CONVERGENCE_TIMEOUT_TICKS = 60;
    private static final float CONVERGENCE_THRESHOLD_DEGREES = 2.0f;

    private final PersonaEntity personaEntity;
    private final PersonaLookControl lookControl;

    // State
    private CompletableFuture<LookResult> activeFuture;
    private Entity trackedEntity;
    private Location staticTarget;
    private boolean shouldTurnBody;
    private boolean isFireAndForget;

    // Timers
    private int convergenceTickCount; // For static look timeout

    public OrientationController(PersonaEntity personaEntity) {
        this.personaEntity = personaEntity;
        this.lookControl = personaEntity.getLookControl();
    }

    /**
     * Initiates a blocking look operation towards a static location.
     */
    public CompletableFuture<LookResult> lookAt(Location target, boolean turnBody) {
        resetState();
        this.activeFuture = new CompletableFuture<>();
        this.staticTarget = target;
        this.shouldTurnBody = turnBody;
        this.isFireAndForget = false;
        // Initial actuation
        updateLookControlTarget(target);
        return activeFuture;
    }

    /**
     * Initiates a fire-and-forget look operation towards a static location.
     */
    public void face(Location target, boolean turnBody) {
        resetState();
        this.staticTarget = target;
        this.shouldTurnBody = turnBody;
        this.isFireAndForget = true;
        updateLookControlTarget(target);
    }

    /**
     * Initiates a fire-and-forget tracking operation on an entity.
     */
    public void face(Entity target) {
        resetState();
        this.trackedEntity = target;
        this.shouldTurnBody = false;
        this.isFireAndForget = true;
        updateLookControlTarget(target.getLocation().add(0, target.getHeight() * 0.85, 0));
    }

    /**
     * Clears the current target and stops looking.
     */
    public void clear() {
        resetState();
        lookControl.stopLooking();
    }

    /**
     * Forces the persona to look at a specific target for this tick only.
     * This bypasses internal state (tracking/static targets) but does NOT clear them.
     * Used by Locomotion maneuvers (e.g., jumping) which require precise facing.
     *
     * @param target
     *            The location to force-face.
     */
    public void tickOverride(Location target) {
        // Maneuvers usually imply physical commitment, so we LOCK the body.
        lookControl.setBodyMode(PersonaLookControl.BodyMode.LOCKED);
        lookControl.setLookAt(target.getX(), target.getY(), target.getZ());
        lookControl.tick();
    }

    /**
     * Main tick loop for orientation logic.
     * 
     * @param isMoving
     *            Whether the entity is currently moving (affects body rotation mode).
     */
    public void tick(boolean isMoving) {
        // Update Target & Validate State
        if (trackedEntity != null) {
            if (!trackedEntity.isValid()) {
                complete(LookResult.TARGET_LOST);
                return;
            }
            // Update target position for moving entity (aim at eyes)
            updateLookControlTarget(trackedEntity.getLocation().add(0, trackedEntity.getHeight() * 0.85, 0));
        } else if (staticTarget != null) {
            // Ensure control has target (redundant but safe)
            updateLookControlTarget(staticTarget);
        }
        // Arbitration: Determine Body Mode
        if (isMoving) {
            lookControl.setBodyMode(PersonaLookControl.BodyMode.EXTERNAL);
        } else {
            lookControl.setBodyMode(shouldTurnBody ? PersonaLookControl.BodyMode.LOCKED : PersonaLookControl.BodyMode.FREE);
        }
        // Actuation: Tick the NMS control
        lookControl.tick();
        // Completion Logic (only if not fire-and-forget)
        if (!isFireAndForget && activeFuture != null && !activeFuture.isDone()) {
            if (trackedEntity != null) {
                // Tracking is now always fire-and-forget or manual clear,
                // but if we ever added blocking tracking back, it would go here.
            } else {
                // Static Convergence Logic
                if (hasConverged()) {
                    complete(LookResult.SUCCESS);
                } else {
                    convergenceTickCount++;
                    if (convergenceTickCount >= CONVERGENCE_TIMEOUT_TICKS) {
                        complete(LookResult.TIMEOUT);
                    }
                }
            }
        }
    }

    private void resetState() {
        if (activeFuture != null && !activeFuture.isDone()) {
            activeFuture.complete(LookResult.CANCELLED);
        }
        activeFuture = null;
        trackedEntity = null;
        staticTarget = null;
        shouldTurnBody = false;
        isFireAndForget = false;
        convergenceTickCount = 0;
    }

    private void complete(LookResult result) {
        if (activeFuture != null && !activeFuture.isDone()) {
            activeFuture.complete(result);
        }
        // If target lost, stop looking physically.
        if (result == LookResult.TARGET_LOST) {
            lookControl.stopLooking();
        }
        // Clear future reference but keep target state active (so agent keeps looking until cleared)
        activeFuture = null;
    }

    private void updateLookControlTarget(Location loc) {
        lookControl.setLookAt(new Vec3(loc.getX(), loc.getY(), loc.getZ()));
    }

    /**
     * Checks if the entity's head rotation matches the target within threshold.
     */
    private boolean hasConverged() {
        if (staticTarget == null)
            return true;
        float currentYaw = personaEntity.getYHeadRot();
        float currentPitch = personaEntity.getXRot();
        double dx = staticTarget.getX() - personaEntity.getX();
        double dy = staticTarget.getY() - personaEntity.getEyeY();
        double dz = staticTarget.getZ() - personaEntity.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float targetPitch = (float) (-Mth.atan2(dy, horizontalDist) * (180.0 / Math.PI));
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float yawDiff = Math.abs(Mth.wrapDegrees(targetYaw - currentYaw));
        float pitchDiff = Math.abs(Mth.wrapDegrees(targetPitch - currentPitch));
        return yawDiff < CONVERGENCE_THRESHOLD_DEGREES && pitchDiff < CONVERGENCE_THRESHOLD_DEGREES;
    }
}