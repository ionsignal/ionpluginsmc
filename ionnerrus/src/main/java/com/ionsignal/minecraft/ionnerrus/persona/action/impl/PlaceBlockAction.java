package com.ionsignal.minecraft.ionnerrus.persona.action.impl;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.action.Action;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.concurrent.CompletableFuture;

public class PlaceBlockAction implements Action {
    private final Material material;
    private final Location targetLocation;
    private final CompletableFuture<ActionStatus> future = new CompletableFuture<>();

    private PersonaEntity personaEntity;
    private ActionStatus status = ActionStatus.RUNNING;
    private boolean executed = false;

    public PlaceBlockAction(Material material, Location targetLocation) {
        this.material = material;
        this.targetLocation = targetLocation;
    }

    @Override
    public void start(PersonaEntity personaEntity) {
        this.personaEntity = personaEntity;
        if (!personaEntity.isAlive()) {
            finish(ActionStatus.FAILURE);
            return;
        }
        PlayerInventory inventory = personaEntity.getBukkitEntity().getInventory();
        if (!inventory.contains(material)) {
            finish(ActionStatus.FAILURE);
            return;
        }
    }

    @Override
    @SuppressWarnings("null")
    public void tick() {
        if (status != ActionStatus.RUNNING || executed) {
            return;
        }
        executed = true;
        PlayerInventory inventory = personaEntity.getBukkitEntity().getInventory();
        int slot = inventory.first(material);
        if (slot == -1) {
            finish(ActionStatus.FAILURE);
            return;
        }
        org.bukkit.inventory.ItemStack bukkitStack = inventory.getItem(slot);
        if (bukkitStack == null) {
            finish(ActionStatus.FAILURE);
            return;
        }
        // Swing arm
        broadcast(new ClientboundAnimatePacket(personaEntity, ClientboundAnimatePacket.SWING_MAIN_HAND));
        // NMS Logic
        ItemStack nmsStack = CraftItemStack.asNMSCopy(bukkitStack);
        // Determine placement context
        // Default to placing on the block BELOW the target air block
        Block blockBelow = targetLocation.getBlock().getRelative(BlockFace.DOWN);
        BlockPos posBelow = new BlockPos(blockBelow.getX(), blockBelow.getY(), blockBelow.getZ());
        // Create a fake "hit result" as if the player right-clicked the top face of the block below.
        BlockHitResult hitResult = new BlockHitResult(
                new Vec3(targetLocation.getX(), targetLocation.getY() - 1, targetLocation.getZ()),
                Direction.UP,
                posBelow,
                false);
        UseOnContext useOnContext = new UseOnContext(personaEntity.level(), personaEntity, InteractionHand.MAIN_HAND, nmsStack, hitResult);
        nmsStack.useOn(useOnContext);
        // Verification and Inventory Update
        if (targetLocation.getBlock().getType() == material) {
            // Decrease the amount in the REAL Bukkit inventory
            bukkitStack.setAmount(bukkitStack.getAmount() - 1);
            inventory.setItem(slot, bukkitStack);
            finish(ActionStatus.SUCCESS);
        } else {
            // Placement failed (obstructed, protected, etc.)
            finish(ActionStatus.FAILURE);
        }
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
    private void broadcast(net.minecraft.network.protocol.Packet<?> packet) {
        if (personaEntity == null)
            return;
        ServerLevel level = personaEntity.level();
        var trackedEntity = level.getChunkSource().chunkMap.entityMap.get(personaEntity.getId());
        if (trackedEntity != null) {
            trackedEntity.broadcast(packet);
        }
    }
}