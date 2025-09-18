package com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.movement;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.JumpControl;

public class PersonaJumpControl extends JumpControl {
    private final LivingEntity entity;
    private boolean jumpRequested;

    public PersonaJumpControl(LivingEntity entity) {
        super(null); // don't use superclass mob field
        this.entity = entity;
    }

    @Override
    public void jump() {
        this.jumpRequested = true;
    }

    /**
     * Explicitly stops any active jump or upward swim maneuver.
     * This should be called by the Navigator when a vertical maneuver is complete.
     */
    public void stop() {
        this.jumpRequested = false;
        this.entity.setJumping(false);
    }

    @Override
    public void tick() {
        // It no longer manages the jump lifecycle or checks for onGround().
        // Its sole responsibility is to sustain the 'isJumping' state if a request is active.
        // It will never set jumping to false on its own, preventing conflicts with the Navigator.
        if (this.jumpRequested) {
            this.entity.setJumping(true);
        }
    }

    public boolean isJumping() {
        return this.jumpRequested;
    }
}