package com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.movement;

import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

public class PersonaMoveControl extends MoveControl {
    protected final PersonaEntity personaEntity;
    // private static final double ARRIVAL_THRESHOLD_SQUARED = 0.1 * 0.1; // 0.01

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
        // This logic now runs continuously as long as a steering target is provided.
        double dx = this.wantedX - this.personaEntity.getX();
        double dz = this.wantedZ - this.personaEntity.getZ();
        // // This acts as a failsafe and prevents jittering.
        // DISABLED FOR NOW TO SEE PROBLEMS
        // double distSq = dx * dx + dz * dz;
        // if (distSq < ARRIVAL_THRESHOLD_SQUARED) {
        // stop();
        // return;
        // }
        float speedModifier = 1.0F;
        float speed = (float) (this.speedModifier * this.personaEntity.getAttributeValue(Attributes.MOVEMENT_SPEED)) * speedModifier;
        float targetYaw = (float) (Mth.atan2(dz, dx) * 180.0F / (float) Math.PI) - 90.0F;
        this.personaEntity.setYRot(this.rotlerp(this.personaEntity.getYRot(), targetYaw, 90.0F));
        // Apply movement
        this.personaEntity.setSpeed(speed);
        this.personaEntity.zza = 1.0F;
    }
}