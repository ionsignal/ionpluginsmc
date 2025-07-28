package com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.movement;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class PersonaLookControl {
    protected final LivingEntity entity;
    protected boolean hasWanted;
    protected float yMaxRotSpeed;
    protected float xMaxRotAngle;
    protected float yHeadRotSpeed;
    protected double wantedX;
    protected double wantedY;
    protected double wantedZ;

    public PersonaLookControl(LivingEntity entity) {
        this.entity = entity;
        this.yMaxRotSpeed = 50.0F;
        this.xMaxRotAngle = 50.0F;
        this.yHeadRotSpeed = 25.0F;
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
            this.entity.setXRot(this.rotlerp(this.entity.getXRot(), targetPitch, this.xMaxRotAngle));
            this.entity.setYHeadRot(this.rotlerp(this.entity.getYHeadRot(), targetYaw, this.yMaxRotSpeed));
        } else {
            // This is the idle `PersonaEntity` look and face behavior
            // When not looking at a specific point, smoothly align head with body and look forward.
            this.entity.setXRot(this.rotlerp(this.entity.getXRot(), 0.0F, 2.0F));
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