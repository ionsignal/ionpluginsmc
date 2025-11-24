package com.ionsignal.minecraft.ionnerrus.persona.movement;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class PersonaLookControl {
    public enum BodyMode {
        FREE, // Head rotates to target, body drags naturally (Vanilla behavior)
        LOCKED, // Body is forced to align with the Head (Aggressive facing)
        EXTERNAL // Body rotation is ignored here (controlled by MoveControl/Navigator)
    }

    protected final LivingEntity entity;
    protected boolean hasWanted;
    protected float yMaxRotSpeed;
    protected float xMaxRotAngle;
    protected float yHeadRotSpeed;
    protected double wantedX;
    protected double wantedY;
    protected double wantedZ;

    private BodyMode bodyMode = BodyMode.FREE;

    public PersonaLookControl(LivingEntity entity) {
        this.entity = entity;
        this.yMaxRotSpeed = 50.0F;
        this.xMaxRotAngle = 50.0F;
        this.yHeadRotSpeed = 25.0F;
    }

    public void setBodyMode(BodyMode mode) {
        this.bodyMode = mode;
    }

    public void setLookAt(Vec3 vec3) {
        this.setLookAt(vec3.x, vec3.y, vec3.z);
    }

    public void setLookAt(double x, double y, double z) {
        this.wantedX = x;
        this.wantedY = y;
        this.wantedZ = z;
        this.hasWanted = true;
    }

    public void stopLooking() {
        this.hasWanted = false;
    }

    public boolean hasWanted() {
        return this.hasWanted;
    }

    public void tick() {
        // We set pitch and head yaw regardless of what the body is doing.
        if (this.hasWanted) {
            double dx = this.wantedX - this.entity.getX();
            double dy = this.wantedY - this.entity.getEyeY();
            double dz = this.wantedZ - this.entity.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            float targetPitch = (float) (-Mth.atan2(dy, horizontalDist) * (180.0 / Math.PI));
            float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            // Always actuate Head
            this.entity.setXRot(this.rotlerp(this.entity.getXRot(), targetPitch, this.xMaxRotAngle));
            this.entity.setYHeadRot(this.rotlerp(this.entity.getYHeadRot(), targetYaw, this.yMaxRotSpeed));
            // Actuate Body based on Mode
            switch (bodyMode) {
                case LOCKED -> {
                    // Force body to match head immediately/aggressively
                    float nextYaw = this.rotlerp(this.entity.yBodyRot, targetYaw, this.yMaxRotSpeed);
                    this.entity.setYBodyRot(nextYaw);
                    this.entity.setYRot(nextYaw);
                }
                case FREE -> {
                    // Let vanilla logic handle natural body drag (handled in LivingEntity.tickHeadTurn via
                    // super.aiStep)
                }
                case EXTERNAL -> {
                    // Do nothing. The MoveControl is driving the body.
                }
            }
        } else {
            // This is the idle `PersonaEntity` look and face behavior
            // When not looking at a specific point, smoothly align head with body and look forward.
            this.entity.setXRot(this.rotlerp(this.entity.getXRot(), 0.0F, 8.0F));
            this.entity.setYHeadRot(this.rotlerp(this.entity.getYHeadRot(), this.entity.yBodyRot, this.yHeadRotSpeed));
        }
    }

    protected float rotlerp(float from, float to, float max) {
        float delta = Mth.wrapDegrees(to - from);
        if (delta > max) {
            delta = max;
        }
        if (delta < -max) {
            delta = -max;
        }
        return from + delta;
    }
}