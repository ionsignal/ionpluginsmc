package com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.movement;

import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

public class PersonaMoveControl extends MoveControl {
    protected final PersonaEntity personaEntity;

    public PersonaMoveControl(PersonaEntity player) {
        super(null);
        this.personaEntity = player;
    }

    @Override
    public void tick() {
        if (this.operation != Operation.MOVE_TO) {
            if (this.operation == Operation.WAIT) {
                this.personaEntity.zza = 0.0F;
            }
            return;
        }
        this.operation = Operation.WAIT;
        double dx = this.wantedX - this.personaEntity.getX();
        double dy = this.wantedY - this.personaEntity.getY();
        double dz = this.wantedZ - this.personaEntity.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < 2.5E-7) {
            this.personaEntity.zza = 0.0F;
            return;
        }
        float speed = (float) (this.speedModifier * this.personaEntity.getAttributeValue(Attributes.MOVEMENT_SPEED));
        float targetYaw = (float) (Mth.atan2(dz, dx) * 180.0F / (float) Math.PI) - 90.0F;
        this.personaEntity.setYRot(this.rotlerp(this.personaEntity.getYRot(), targetYaw, 90.0F));

        // Apply to zza for travel 
        this.personaEntity.setSpeed(speed);
        this.personaEntity.zza = 1.0F;
        //this.personaEntity.xxa // future strafe
    }
}