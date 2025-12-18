package com.ionsignal.minecraft.ionnerrus.persona.movement;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

public class PersonaMoveControl extends MoveControl {
    protected final PersonaEntity personaEntity;
    private static final float SHARP_TURN_ANGLE_THRESHOLD = 45.0F;
    private static final float MAX_TURN_SPEED_FAST = 75.0F;
    private static final float MAX_TURN_SPEED_SLOW = 20.0F;

    public PersonaMoveControl(PersonaEntity player) {
        super(null);
        this.personaEntity = player;
    }

    /**
     * Explicitly stops all movement and sets the state to WAIT.
     * This is the correct way to halt the Persona's movement from the Navigator.
     */
    public void stop() {
        this.operation = Operation.WAIT;
        this.personaEntity.zza = 0.0F;
    }

    @Override
    public void tick() {
        if (this.operation != Operation.MOVE_TO) {
            if (this.operation == Operation.WAIT) {
                this.personaEntity.zza = 0.0F;
            }
            return;
        }
        // We use isSprinting() as the discriminator for Deep Swimming vs Surface/Wading.
        // LocomotionController guarantees this flag is set ONLY for deep swimming.
        if (this.personaEntity.isInWater() && this.personaEntity.isSprinting()) {
            tickDeepSwim();
        } else {
            tickStandardMovement();
        }
    }

    /**
     * Handles 3D vector movement for Deep Swimming.
     * Controls Pitch, Yaw, and Body Rotation to simulate "Dolphin" physics.
     */
    @SuppressWarnings("null")
    private void tickDeepSwim() {
        double dx = this.wantedX - this.personaEntity.getX();
        double dy = this.wantedY - this.personaEntity.getY();
        double dz = this.wantedZ - this.personaEntity.getZ();
        // Stop if we are practically there to prevent jittering
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < 0.01) {
            this.personaEntity.zza = 0.0f;
            return;
        }
        // atan2(z, x) gives radians. Convert to degrees. Subtract 90 to align with Minecraft's South=0
        // system.
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        // atan2(y, dist) gives radians. Convert to degrees. Negate because Minecraft Up is -90.
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float targetPitch = (float) -(Mth.atan2(dy, horizontalDist) * (180.0D / Math.PI));
        // We force Head, Body, and Facing to match the vector perfectly.
        this.personaEntity.setYRot(targetYaw);
        this.personaEntity.setYHeadRot(targetYaw);
        this.personaEntity.setYBodyRot(targetYaw);
        this.personaEntity.setXRot(targetPitch);
        // Set the movement speed attribute and apply forward input (zza)
        float attributeSpeed = (float) this.personaEntity.getAttributeValue(Attributes.MOVEMENT_SPEED);
        this.personaEntity.setSpeed(attributeSpeed);
        this.personaEntity.zza = (float) this.speedModifier;
    }

    /**
     * Handles 2D planar movement for Walking, Wading, and Surface Swimming.
     * Controls Yaw only. ABSTAINS from touching Pitch ($XRot$) to allow OrientationController
     * to handle social looking / horizon gazing.
     */
    @SuppressWarnings("null")
    private void tickStandardMovement() {
        double dx = this.wantedX - this.personaEntity.getX();
        double dz = this.wantedZ - this.personaEntity.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq < 2.500000277905201E-7D) {
            this.personaEntity.zza = 0.0f;
            return;
        }
        float throttle = (float) this.speedModifier;
        float attributeSpeed = (float) this.personaEntity.getAttributeValue(Attributes.MOVEMENT_SPEED);
        // Calculate Yaw (YRot)
        float targetYaw = (float) (Mth.atan2(dz, dx) * 180.0F / (float) Math.PI) - 90.0F;
        float yawDifference = Mth.wrapDegrees(targetYaw - this.personaEntity.getYRot());
        float maxTurnSpeed = Math.abs(yawDifference) > SHARP_TURN_ANGLE_THRESHOLD
                ? MAX_TURN_SPEED_FAST
                : MAX_TURN_SPEED_SLOW;
        this.personaEntity.setYRot(this.rotlerp(this.personaEntity.getYRot(), targetYaw, maxTurnSpeed));
        // Apply Throttle
        this.personaEntity.setSpeed(attributeSpeed);
        this.personaEntity.zza = throttle;
    }

    public Operation getOperation() {
        return this.operation;
    }
}