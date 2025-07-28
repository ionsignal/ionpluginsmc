package com.ionsignal.minecraft.ionnerrus.persona.action.impl;

import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.action.Action;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;
import net.minecraft.util.Mth;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public class FaceHeadBodyAction implements Action {
    private final @Nullable Location staticTarget;
    private final @Nullable Entity dynamicTarget;
    private final boolean turnBody;
    private final int durationTicks;
    private final CompletableFuture<ActionStatus> future = new CompletableFuture<>();

    private PersonaEntity personaEntity;
    private ActionStatus status = ActionStatus.RUNNING;
    private int ticksElapsed = 0;

    // Constructor for a static Location
    public FaceHeadBodyAction(Location target, boolean turnBody, int durationTicks) {
        this.staticTarget = target;
        this.dynamicTarget = null;
        this.turnBody = turnBody;
        this.durationTicks = durationTicks;
    }

    // Constructor for a dynamic Entity
    public FaceHeadBodyAction(Entity target, boolean turnBody, int durationTicks) {
        this.staticTarget = null;
        this.dynamicTarget = target;
        this.turnBody = turnBody;
        this.durationTicks = durationTicks;
    }

    @Override
    public void start(Persona persona) {
        if (!persona.isSpawned() || (staticTarget == null && (dynamicTarget == null || !dynamicTarget.isValid()))) {
            finish(ActionStatus.FAILURE);
            return;
        }
        this.personaEntity = persona.getPersonaEntity();
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING)
            return;
        ticksElapsed++;
        if (durationTicks > 0 && ticksElapsed >= durationTicks) {
            finish(ActionStatus.SUCCESS);
            return;
        }
        if (personaEntity == null || !personaEntity.isAlive()) {
            finish(ActionStatus.FAILURE);
            return;
        }
        Location currentTarget;
        if (dynamicTarget != null) {
            if (!dynamicTarget.isValid()) {
                finish(ActionStatus.FAILURE);
                return;
            }
            currentTarget = dynamicTarget.getLocation().add(0, dynamicTarget.getHeight() * 0.85, 0);
        } else {
            currentTarget = this.staticTarget;
        }
        if (currentTarget == null) {
            finish(ActionStatus.FAILURE);
            return;
        }

        Vector dir = currentTarget.toVector().subtract(personaEntity.getBukkitEntity().getEyeLocation().toVector());
        double horizontalDist = Math.sqrt(dir.getX() * dir.getX() + dir.getZ() * dir.getZ());
        float targetPitch = (float) (-Mth.atan2(dir.getY(), horizontalDist) * (180.0 / Math.PI));
        float targetYaw = (float) (Mth.atan2(dir.getZ(), dir.getX()) * (180.0 / Math.PI)) - 90.0F;

        personaEntity.setXRot(rotlerp(personaEntity.getXRot(), targetPitch, 50.0f));
        personaEntity.setYHeadRot(rotlerp(personaEntity.getYHeadRot(), targetYaw, 50.0f));
        if (turnBody) {
            personaEntity.setYRot(rotlerp(personaEntity.getYRot(), targetYaw, 25.0f));
        }
        if (durationTicks <= 0 && dynamicTarget == null) { // Only auto-complete for static targets
            float yawDiff = Mth.wrapDegrees(targetYaw - personaEntity.getYHeadRot());
            float pitchDiff = Mth.wrapDegrees(targetPitch - personaEntity.getXRot());
            if (Math.abs(yawDiff) < 1.0f && Math.abs(pitchDiff) < 1.0f) {
                finish(ActionStatus.SUCCESS);
            }
        }
    }

    @Override
    public void stop() {
        finish(ActionStatus.CANCELLED);
    }

    private void finish(ActionStatus finalStatus) {
        if (this.status == ActionStatus.RUNNING) {
            this.status = finalStatus;
            this.future.complete(finalStatus);
        }
    }

    private float rotlerp(float from, float to, float max) {
        float delta = Mth.wrapDegrees(to - from);
        if (delta > max)
            delta = max;
        if (delta < -max)
            delta = -max;
        return from + delta;
    }

    @Override
    public ActionStatus getStatus() {
        return status;
    }

    @Override
    public CompletableFuture<ActionStatus> getFuture() {
        return future;
    }
}