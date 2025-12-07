package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.EquipBestToolResult;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.ActionResult;

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
public class EquipBestToolSkill implements Skill<EquipBestToolResult> {

    private final Block targetBlock;

    public EquipBestToolSkill(Block targetBlock) {
        this.targetBlock = targetBlock;
    }

    // Internal record to pass data between the analysis phase and execution phase
    private record ToolSwapRequest(int sourceSlot, int destSlot, Material material, boolean success) {
    }

    @Override
    @SuppressWarnings("null")
    public CompletableFuture<EquipBestToolResult> execute(NerrusAgent agent, ExecutionToken token) {
        // Analyze inventory on the main thread to determine what needs to happen.
        return CompletableFuture.supplyAsync(() -> {
            PlayerInventory inventory = agent.getPersona().getInventory();
            if (inventory == null) {
                return new ToolSwapRequest(-1, -1, null, false);
            }
            // Find the best tool currently in the inventory
            // Note: Accessing NMS block state via CraftWorld is generally safe for reads,
            // but keeping it on main thread is best practice.
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
                        // This direct modification is safe here because we are on the main thread.
                        ItemStack defaultTool = new ItemStack(defaultToolType);
                        if (inventory.firstEmpty() != -1) {
                            inventory.addItem(defaultTool);
                            bestToolSlot = inventory.first(defaultToolType);
                        } else {
                            // No space for the default tool, fail the skill.
                            return new ToolSwapRequest(-1, -1, null, false);
                        }
                    }
                }
            }
            // Determine if a swap is actually needed
            int mainHandSlot = inventory.getHeldItemSlot();
            if (bestToolSlot == -1) {
                // No tool found, and no default could be provided.
                // We return success=true here but with slot -1 to indicate "No Tool Available" logic in next step
                // or we can handle it as a specific failure case.
                // However, if bestToolSlot is -1, it means we are using hands.
                // If the block REQUIRES a tool (e.g. diamond ore), this might be an issue,
                // but for general gathering, hands are valid.
                // Let's assume hands are valid if no tool found.
                ItemStack held = inventory.getItem(mainHandSlot);
                Material heldType = held != null ? held.getType() : Material.AIR;
                return new ToolSwapRequest(-1, -1, heldType, true);
            }
            Material toolType = inventory.getItem(bestToolSlot).getType();
            if (bestToolSlot == mainHandSlot) {
                // No swap needed (already holding it)
                return new ToolSwapRequest(-1, -1, toolType, true);
            }
            return new ToolSwapRequest(bestToolSlot, mainHandSlot, toolType, true);
        }, IonNerrus.getInstance().getMainThreadExecutor())
                .thenCompose(request -> {
                    // Step 2: Execute the swap if needed via the PhysicalBody
                    if (!request.success) {
                        return CompletableFuture.completedFuture(EquipBestToolResult.failure());
                    }
                    if (request.sourceSlot == -1) {
                        // Success, but no swap needed (either hands or already equipped)
                        return CompletableFuture.completedFuture(EquipBestToolResult.success(request.material));
                    }
                    // Delegate the physical action to the body
                    return agent.getPersona().getPhysicalBody().actions()
                            .swapItems(request.sourceSlot, request.destSlot, token)
                            .thenApply(result -> result == ActionResult.SUCCESS
                                    ? EquipBestToolResult.success(request.material)
                                    : EquipBestToolResult.failure());
                });
    }

    private Material getDefaultTool(Material blockMaterial) {
        if (Tag.LOGS.isTagged(blockMaterial)) {
            return Material.STONE_AXE;
        }
        if (Tag.BASE_STONE_OVERWORLD.isTagged(blockMaterial) || Tag.STONE_BRICKS.isTagged(blockMaterial)
                || blockMaterial == Material.COBBLESTONE || blockMaterial == Material.STONE) {
            return Material.STONE_PICKAXE;
        }
        if (blockMaterial == Material.DIRT || blockMaterial == Material.GRASS_BLOCK || blockMaterial == Material.SAND
                || blockMaterial == Material.GRAVEL) {
            return Material.STONE_SHOVEL;
        }
        return null;
    }
}