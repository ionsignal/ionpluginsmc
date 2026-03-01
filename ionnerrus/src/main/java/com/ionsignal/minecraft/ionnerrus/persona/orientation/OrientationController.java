package com.ionsignal.minecraft.ionnerrus.persona.orientation;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.LookResult;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl.BodyMode;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

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

    // Cleanup handle for the active token
    private ExecutionToken.Registration tokenRegistration;
    private boolean isOverridden;

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
    public CompletableFuture<LookResult> lookAt(Location target, boolean turnBody, ExecutionToken token) {
        resetState();
        bindToken(token);
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
    public void face(Location target, boolean turnBody, ExecutionToken token) {
        resetState();
        bindToken(token);
        this.staticTarget = target;
        this.shouldTurnBody = turnBody;
        this.isFireAndForget = true;
        updateLookControlTarget(target);
    }

    /**
     * Initiates a fire-and-forget tracking operation on an entity.
     */
    public void face(Entity target, ExecutionToken token) {
        resetState();
        bindToken(token);
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
     * Retrieves the orientation intent derived from the current active Skill.
     * This represents "Cognitive Desire".
     */
    public OrientationIntent getCurrentSkillIntent() {
        if (trackedEntity != null) {
            if (!trackedEntity.isValid()) {
                complete(LookResult.TARGET_LOST);
                return new OrientationIntent.Idle();
            }
            return new OrientationIntent.TrackEntity(trackedEntity, BodyMode.EXTERNAL); // Tracking usually implies body follows movement
        } else if (staticTarget != null) {
            BodyMode mode = shouldTurnBody ? BodyMode.LOCKED : BodyMode.FREE;
            return new OrientationIntent.FocusOnLocation(staticTarget, mode);
        }
        return new OrientationIntent.Idle();
    }

    /**
     * Applies the final arbitrated intent to the NMS entity.
     * This is the single point of truth for NMS rotation modification.
     */
    public void actuate(OrientationIntent intent) {
        // Reset override flag for next tick
        this.isOverridden = false;
        switch (intent) {
            case OrientationIntent.Idle idle -> {
                lookControl.stopLooking();
                // Check convergence if we were supposed to be looking at something but aren't
                checkConvergence();
            }
            case OrientationIntent.FocusOnLocation focus -> {
                lookControl.setBodyMode(focus.mode());
                updateLookControlTarget(focus.target());
                lookControl.tick();
                checkConvergence();
            }
            case OrientationIntent.TrackEntity track -> {
                lookControl.setBodyMode(track.mode());
                // Aim at eyes
                Location targetLoc = track.target().getLocation().add(0, track.target().getHeight() * 0.85, 0);
                updateLookControlTarget(targetLoc);
                lookControl.tick();
                // Tracking is continuous, no convergence check needed usually
            }
            case OrientationIntent.AlignToHeading align -> {
                if (align.snap()) {
                    // Clear any previous look target to prevent
                    lookControl.stopLooking();
                    // Snap instantly
                    Vector dir = align.heading();
                    float targetYaw = (float) (Math.toDegrees(Math.atan2(dir.getZ(), dir.getX())) - 90.0);
                    // Bypass interpolation
                    personaEntity.setYRot(targetYaw);
                    personaEntity.setYHeadRot(targetYaw);
                    if (align.mode() == BodyMode.LOCKED) {
                        personaEntity.setYBodyRot(targetYaw);
                    }
                    // We do NOT call lookControl.tick() here because we manually set the fields.
                } else {
                    // Smooth align using look control
                    Location dummyTarget = personaEntity.getBukkitEntity().getLocation().add(align.heading().multiply(5));
                    lookControl.setBodyMode(align.mode());
                    updateLookControlTarget(dummyTarget);
                    lookControl.tick();
                }
                // No convergence check for alignments (usually physics driven)
            }
            default -> {
                // no-op
            }
        }
    }

    /**
     * Called by the Arbiter (PhysicalBody) when Physics overrides Cognition.
     * Pauses the timeout counter for the active skill.
     */
    public void notifyOverride() {
        this.isOverridden = true;
    }

    private void checkConvergence() {
        if (isFireAndForget || activeFuture == null || activeFuture.isDone()) {
            return;
        }
        // If overridden by physics, do not increment timeout or check convergence
        if (isOverridden) {
            return;
        }
        if (hasConverged()) {
            complete(LookResult.SUCCESS);
        } else {
            convergenceTickCount++;
            if (convergenceTickCount >= CONVERGENCE_TIMEOUT_TICKS) {
                complete(LookResult.TIMEOUT);
            }
        }
    }

    private void resetState() {
        if (tokenRegistration != null) {
            // Clean up previous listener to prevent memory leaks
            tokenRegistration.close();
            tokenRegistration = null;
        }
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

    private void bindToken(ExecutionToken token) {
        this.tokenRegistration = token.onCancel(() -> {
            // Ensure cancellation happens on main thread to safely interact with NMS controls
            if (Bukkit.isPrimaryThread()) {
                this.clear();
            } else {
                CompletableFuture.runAsync(this::clear, IonNerrus.getInstance().getMainThreadExecutor());
            }
        });
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