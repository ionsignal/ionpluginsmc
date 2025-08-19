package com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.movement;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.JumpControl;

public class PersonaJumpControl extends JumpControl {
    private final LivingEntity entity;
    private boolean jumpRequested;
    private int jumpTicks;

    public PersonaJumpControl(LivingEntity entity) {
        super(null); // don't use superclass mob field
        this.entity = entity;
    }

    @Override
    public void jump() {
        // This prevents resetting ticks if jump() is called again while already jumping.
        if (!this.jumpRequested) {
            this.jumpRequested = true;
            this.jumpTicks = 0;
        }
    }

    @Override
    public void tick() {
        if (this.jumpRequested) {
            this.entity.setJumping(true);
            this.jumpTicks++;
            // Keep jump active for up to 3 ticks or until the entity leaves the ground.
            // This gives the server more time to process the jump.
            if (this.jumpTicks >= 3 || !this.entity.onGround()) {
                this.jumpRequested = false;
                this.entity.setJumping(false);
            }
        } else {
            // Ensure jumping is false if not requested.
            this.entity.setJumping(false);
        }
    }

    public boolean isJumping() {
        return this.jumpRequested;
    }
}