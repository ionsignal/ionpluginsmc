package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.concurrent.CompletableFuture;

/**
 * A skill to find the best tool in the agent's inventory for a given block
 * and equip it in the main hand.
 */
public class EquipBestToolSkill implements Skill<Boolean> {

    private final Block targetBlock;

    public EquipBestToolSkill(Block targetBlock) {
        this.targetBlock = targetBlock;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        PlayerInventory inventory = agent.getPersona().getInventory();
        if (inventory == null) {
            return CompletableFuture.completedFuture(false);
        }

        BlockState nmsBlockState = ((CraftWorld) targetBlock.getWorld()).getHandle()
                .getBlockState(new BlockPos(targetBlock.getX(), targetBlock.getY(), targetBlock.getZ()));

        int bestToolSlot = -1;
        float bestSpeed = 1.0f; // Speed with bare hands

        // Check main inventory slots (0-35)
        for (int i = 0; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType().isAir()) {
                continue;
            }

            net.minecraft.world.item.ItemStack nmsItem = CraftItemStack.asNMSCopy(item);
            float speed = nmsItem.getDestroySpeed(nmsBlockState);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestToolSlot = i;
            }
        }

        int mainHandSlot = inventory.getHeldItemSlot();
        ItemStack mainHandItem = inventory.getItem(mainHandSlot);

        // If no better tool was found, we're done.
        if (bestToolSlot == -1) {
            return CompletableFuture.completedFuture(true);
        }

        // If the best tool is already in the main hand, we're done.
        if (bestToolSlot == mainHandSlot) {
            return CompletableFuture.completedFuture(true);
        }

        // Swap the best tool into the main hand
        ItemStack bestTool = inventory.getItem(bestToolSlot);
        inventory.setItem(mainHandSlot, bestTool);
        inventory.setItem(bestToolSlot, mainHandItem);

        return CompletableFuture.completedFuture(true);
    }
}