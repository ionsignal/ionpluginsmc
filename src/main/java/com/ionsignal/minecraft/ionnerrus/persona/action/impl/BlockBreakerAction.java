package com.ionsignal.minecraft.ionnerrus.persona.action.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.action.Action;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus;
import com.ionsignal.minecraft.ionnerrus.persona.animation.PlayerAnimation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.concurrent.CompletableFuture;

public class BlockBreakerAction implements Action {
    private static final int SWING_INTERVAL = 10;
    private final Block block;
    private final CompletableFuture<ActionStatus> future = new CompletableFuture<>();
    private int ticksToBreak;

    private FaceHeadBodyAction faceAction;
    private ActionStatus status = ActionStatus.RUNNING;
    private Persona persona;
    private int ticksElapsed = 0;
    private int ticksSinceLastSwing = 0;

    public BlockBreakerAction(Block block) {
        this.block = block;
    }

    @Override
    public void start(Persona persona) {
        this.persona = persona;
        this.ticksSinceLastSwing = SWING_INTERVAL;

        // look at the block we're breaking
        Location lookTarget = block.getLocation().clone().add(0.5, 0.5, 0.5);
        this.faceAction = new FaceHeadBodyAction(lookTarget, true, Integer.MAX_VALUE);
        this.faceAction.start(persona);

        if (block.getType() == Material.AIR) {
            finish(ActionStatus.SUCCESS);
            return;
        }

        var bridge = persona.getManager().getPlatformBridge();
        this.ticksToBreak = bridge.calculateBreakTicks(persona, this.block);
        if (this.ticksToBreak == Integer.MAX_VALUE) {
            IonNerrus.getInstance().getLogger()
                    .warning("Persona " + persona.getName() + " tried to break an unbreakable block: " + block.getType());
            finish(ActionStatus.FAILURE);
            return;
        }

        // Handle instant-break blocks
        if (this.ticksToBreak <= 1) {
            boolean success = bridge.destroyBlock(persona, this.block);
            finish(success ? ActionStatus.SUCCESS : ActionStatus.FAILURE);
        }
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        if (this.faceAction != null) {
            this.faceAction.tick();
        }
        ticksElapsed++;
        ticksSinceLastSwing++;
        if (ticksSinceLastSwing >= SWING_INTERVAL) {
            persona.playAnimation(PlayerAnimation.SWING_MAIN_ARM);
            ticksSinceLastSwing = 0;
        }
        int stage = (int) (((double) ticksElapsed / this.ticksToBreak) * 10.0);
        persona.getManager().getPlatformBridge().sendBlockBreakAnimation(persona, block, stage);
        if (ticksElapsed >= this.ticksToBreak) {
            IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                if (persona.isSpawned() && block.getType() != Material.AIR) {
                    var bridge = persona.getManager().getPlatformBridge();
                    boolean success = bridge.destroyBlock(persona, this.block);
                    if (success) {
                        finish(ActionStatus.SUCCESS);
                    } else {
                        // This can happen if a plugin cancels the BlockBreakEvent, etc.
                        finish(ActionStatus.FAILURE);
                    }
                } else {
                    // If the block is already air or the entity is invalid, it's a success.
                    finish(ActionStatus.SUCCESS);
                }
            });
        }
    }

    @Override
    public void stop() {
        finish(ActionStatus.CANCELLED);
    }

    private void finish(ActionStatus finalStatus) {
        if (this.status == ActionStatus.RUNNING) {
            this.status = finalStatus;
            if (this.faceAction != null) {
                this.faceAction.stop();
            }
            this.future.complete(finalStatus);
            persona.getManager().getPlatformBridge().sendBlockBreakAnimation(persona, block, -1);
        }
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