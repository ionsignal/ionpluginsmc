package com.ionsignal.minecraft.ionnerrus.persona.action.impl;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.action.Action;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus;

import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerLevel;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.concurrent.CompletableFuture;

public class SwapItemAction implements Action {
    private final int sourceSlot;
    private final int destinationSlot;
    private final CompletableFuture<ActionStatus> future = new CompletableFuture<>();
    private ActionStatus status = ActionStatus.RUNNING;

    public SwapItemAction(int sourceSlot, int destinationSlot) {
        this.sourceSlot = sourceSlot;
        this.destinationSlot = destinationSlot;
    }

    @Override
    public void start(PersonaEntity personaEntity) {
        if (!personaEntity.isAlive()) {
            finish(ActionStatus.FAILURE);
            return;
        }
        PlayerInventory inventory = personaEntity.getBukkitEntity().getInventory();
        ItemStack sourceItem = inventory.getItem(sourceSlot);
        ItemStack destItem = inventory.getItem(destinationSlot);
        inventory.setItem(destinationSlot, sourceItem);
        inventory.setItem(sourceSlot, destItem);
        // Visual feedback
        broadcast(personaEntity, new ClientboundAnimatePacket(personaEntity, ClientboundAnimatePacket.SWING_MAIN_HAND));
        finish(ActionStatus.SUCCESS);
    }

    @Override
    public void tick() {
        // Instant action, logic handled in start
    }

    @Override
    public void stop() {
        finish(ActionStatus.CANCELLED);
    }

    @Override
    public ActionStatus getStatus() {
        return status;
    }

    @Override
    public CompletableFuture<ActionStatus> getFuture() {
        return future;
    }

    private void finish(ActionStatus finalStatus) {
        if (this.status == ActionStatus.RUNNING) {
            this.status = finalStatus;
            this.future.complete(finalStatus);
        }
    }

    @SuppressWarnings("null")
    private void broadcast(PersonaEntity entity, net.minecraft.network.protocol.Packet<?> packet) {
        ServerLevel level = entity.level();
        var trackedEntity = level.getChunkSource().chunkMap.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            for (var connection : trackedEntity.seenBy) {
                connection.send(packet);
            }
        }
    }
}