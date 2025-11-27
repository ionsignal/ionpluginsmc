package com.ionsignal.minecraft.ionnerrus.persona.movement;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

public class PersonaMoveControl extends MoveControl {
    protected final PersonaEntity personaEntity;
    private static final double DEAD_ZONE_THRESHOLD_SQUARED = 0.5 * 0.5;
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
    @SuppressWarnings("null")
    public void tick() {
        if (this.operation != Operation.MOVE_TO) {
            if (this.operation == Operation.WAIT) {
                this.personaEntity.zza = 0.0F;
            }
            return;
        }
        // This logic now runs continuously as long as a steering target is provided.
        double dx = this.wantedX - this.personaEntity.getX();
        double dz = this.wantedZ - this.personaEntity.getZ();

        float speedModifier = 1.0F;
        float speed = (float) (this.speedModifier * this.personaEntity.getAttributeValue(Attributes.MOVEMENT_SPEED)) * speedModifier;
        // float targetYaw = (float) (Mth.atan2(dz, dx) * 180.0F / (float) Math.PI) - 90.0F;
        // // Apply movement
        // this.personaEntity.setYRot(this.rotlerp(this.personaEntity.getYRot(), targetYaw, 90.0F));
        double distSq = dx * dx + dz * dz;
        if (distSq > DEAD_ZONE_THRESHOLD_SQUARED) {
            float targetYaw = (float) (Mth.atan2(dz, dx) * 180.0F / (float) Math.PI) - 90.0F;
            // Principle 2: Adaptive Speed. If we are allowed to turn, decide how fast.
            float yawDifference = Mth.wrapDegrees(targetYaw - this.personaEntity.getYRot());
            float maxTurnSpeed = Math.abs(yawDifference) > SHARP_TURN_ANGLE_THRESHOLD
                    ? MAX_TURN_SPEED_FAST
                    : MAX_TURN_SPEED_SLOW;
            this.personaEntity.setYRot(this.rotlerp(this.personaEntity.getYRot(), targetYaw, maxTurnSpeed));
        }
        this.personaEntity.setSpeed(speed);
        this.personaEntity.zza = 1.0F;
    }

    public Operation getOperation() {
        return this.operation;
    }
}