package com.ionsignal.minecraft.ionnerrus.persona.action.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.action.Action;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus;

import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.concurrent.CompletableFuture;

public class BlockBreakerAction implements Action {
    private static final int SWING_INTERVAL = 10;

    private final Block block;
    private final CompletableFuture<ActionStatus> future = new CompletableFuture<>();

    private int ticksToBreak;
    private PersonaEntity personaEntity;
    private int ticksElapsed = 0;
    private int ticksSinceLastSwing = 0;
    private ActionStatus status = ActionStatus.RUNNING;

    public BlockBreakerAction(Block block) {
        this.block = block;
    }

    @Override
    public void start(PersonaEntity personaEntity) {
        this.personaEntity = personaEntity;
        this.ticksSinceLastSwing = SWING_INTERVAL;
        if (block.getType() == Material.AIR) {
            finish(ActionStatus.SUCCESS);
            return;
        }
        this.ticksToBreak = calculateBreakTicks(this.block);
        if (this.ticksToBreak == Integer.MAX_VALUE) {
            IonNerrus.getInstance().getLogger()
                    .warning("PersonaEntity " + personaEntity.getUUID() + " tried to break an unbreakable block: " + block.getType());
            finish(ActionStatus.FAILURE);
            return;
        }
        if (this.ticksToBreak <= 1) {
            // Instant break
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ());
            boolean success = personaEntity.gameMode.destroyBlock(pos);
            finish(success ? ActionStatus.SUCCESS : ActionStatus.FAILURE);
        }
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        ticksElapsed++;
        ticksSinceLastSwing++;
        if (ticksSinceLastSwing >= SWING_INTERVAL) {
            broadcast(new ClientboundAnimatePacket(personaEntity, ClientboundAnimatePacket.SWING_MAIN_HAND));
            ticksSinceLastSwing = 0;
        }
        // Sanity check: Is the block still there?
        if (block.getType() == Material.AIR) {
            finish(ActionStatus.SUCCESS);
            return;
        }
        int stage = (int) (((double) ticksElapsed / this.ticksToBreak) * 10.0);
        broadcast(new ClientboundBlockDestructionPacket(personaEntity.getId(),
                new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ()), stage));

        if (ticksElapsed >= this.ticksToBreak) {
            if (personaEntity.isAlive() && block.getType() != Material.AIR) {
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ());
                boolean success = personaEntity.gameMode.destroyBlock(pos);
                finish(success ? ActionStatus.SUCCESS : ActionStatus.FAILURE);
            } else {
                finish(ActionStatus.SUCCESS);
            }
        }
    }

    @Override
    public void stop() {
        finish(ActionStatus.CANCELLED);
    }

    private void finish(ActionStatus finalStatus) {
        if (this.status == ActionStatus.RUNNING) {
            this.status = finalStatus;
            this.future.complete(finalStatus);
            // Reset break animation
            broadcast(new ClientboundBlockDestructionPacket(personaEntity.getId(),
                    new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ()), -1));
        }
    }

    private int calculateBreakTicks(Block block) {
        ServerLevel level = personaEntity.level();
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ());
        net.minecraft.world.level.block.state.BlockState nmsBlockState = level.getBlockState(blockPos);
        float hardness = nmsBlockState.getDestroySpeed(level, blockPos);
        if (hardness < 0)
            return Integer.MAX_VALUE;
        if (hardness == 0)
            return 1;

        float destroySpeed = personaEntity.getDestroySpeed(nmsBlockState);
        boolean hasCorrectTool = personaEntity.hasCorrectToolForDrops(nmsBlockState);
        float divisor = hasCorrectTool ? 30.0f : 100.0f;
        float damagePerTick = destroySpeed / hardness / divisor;
        if (damagePerTick <= 0)
            return Integer.MAX_VALUE;
        return (int) Math.ceil(1.0 / damagePerTick);
    }

    @SuppressWarnings("null")
    private void broadcast(net.minecraft.network.protocol.Packet<?> packet) {
        if (personaEntity == null)
            return;
        ServerLevel level = personaEntity.level();
        var trackedEntity = level.getChunkSource().chunkMap.entityMap.get(personaEntity.getId());
        if (trackedEntity != null) {
            trackedEntity.broadcast(packet);
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