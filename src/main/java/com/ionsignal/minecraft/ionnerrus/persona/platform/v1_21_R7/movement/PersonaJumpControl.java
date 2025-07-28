package com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.movement;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.JumpControl;

public class PersonaJumpControl extends JumpControl {
    private final LivingEntity entity;
    private boolean jump;

    public PersonaJumpControl(LivingEntity entity) {
        super(null); // Pass null, we don't use the superclass's mob field.
        this.entity = entity;
    }

    @Override
    public void jump() {
        this.jump = true;
    }

    @Override
    public void tick() {
        this.entity.setJumping(this.jump);
        this.jump = false;
    }
}