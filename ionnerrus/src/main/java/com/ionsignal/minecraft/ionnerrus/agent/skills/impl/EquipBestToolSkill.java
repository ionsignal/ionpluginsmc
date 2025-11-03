package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.bukkit.Tag;
import org.bukkit.Material;
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
        // Find the best tool currently in the inventory
        BlockState nmsBlockState = ((CraftWorld) targetBlock.getWorld()).getHandle()
                .getBlockState(new BlockPos(targetBlock.getX(), targetBlock.getY(), targetBlock.getZ()));
        int bestToolSlot = -1;
        float bestSpeed = 1.0f; // Speed with bare hands
        for (int i = 0; i < 36; i++) { // Check main inventory slots
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
        // If no tool is better than hands, determine if we should provide a default one.
        if (bestToolSlot == -1) {
            Material defaultToolType = getDefaultTool(targetBlock.getType());
            if (defaultToolType != null) {
                // Check if the agent already has this tool somewhere
                int existingDefaultToolSlot = inventory.first(defaultToolType);
                if (existingDefaultToolSlot != -1) {
                    bestToolSlot = existingDefaultToolSlot;
                } else {
                    // Agent needs the tool and doesn't have it. Give it one if there's space.
                    ItemStack defaultTool = new ItemStack(defaultToolType);
                    if (inventory.firstEmpty() != -1) {
                        inventory.addItem(defaultTool);
                        bestToolSlot = inventory.first(defaultToolType);
                    } else {
                        // No space for the default tool, fail the skill.
                        return CompletableFuture.completedFuture(false);
                    }
                }
            }
        }
        // Equip the best tool if necessary
        int mainHandSlot = inventory.getHeldItemSlot();
        if (bestToolSlot == -1) {
            return CompletableFuture.completedFuture(true);
        }
        if (bestToolSlot == mainHandSlot) {
            return CompletableFuture.completedFuture(true);
        }
        // Swap the best tool into the main hand
        ItemStack bestTool = inventory.getItem(bestToolSlot);
        ItemStack mainHandItem = inventory.getItem(mainHandSlot);
        inventory.setItem(mainHandSlot, bestTool);
        inventory.setItem(bestToolSlot, mainHandItem);
        return CompletableFuture.completedFuture(true);
    }

    private Material getDefaultTool(Material blockMaterial) {
        if (Tag.LOGS.isTagged(blockMaterial)) {
            return Material.STONE_AXE;
        }
        if (Tag.BASE_STONE_OVERWORLD.isTagged(blockMaterial) || Tag.STONE_BRICKS.isTagged(blockMaterial)
                || blockMaterial == Material.COBBLESTONE || blockMaterial == Material.STONE) {
            return Material.STONE_PICKAXE;
        }
        // Add more mappings as needed (e.g., for dirt -> shovel)
        return null;
    }
}