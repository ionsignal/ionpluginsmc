package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus;
import com.ionsignal.minecraft.ionnerrus.persona.action.impl.BlockBreakerAction;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.concurrent.CompletableFuture;

public class BreakBlockSkill implements Skill<Boolean> {

    private final Location blockLocation;

    public BreakBlockSkill(Location blockLocation) {
        this.blockLocation = blockLocation;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        Persona persona = agent.getPersona();
        Block block = blockLocation.getBlock();
        if (!persona.isSpawned() || block.isEmpty()) {
            return CompletableFuture.completedFuture(true); // Already done, success.
        }
        double breakReachSquared = 7.1 * 7.1;
        if (persona.getLocation().distanceSquared(blockLocation.clone().add(0.5, 0.5, 0.5)) > breakReachSquared) {
            return CompletableFuture.completedFuture(false);
        }
        // TODO: The original skill equipped an item. This should be part of the action itself.
        // ItemStack axe = new ItemStack(Material.WOODEN_AXE);
        // axe.addUnsafeEnchantment(Enchantment.EFFICIENCY, 1);
        // if (persona.getEntity() instanceof LivingEntity livingEntity && livingEntity.getEquipment() != null) {
        // livingEntity.getEquipment().setItemInMainHand(axe);
        // }
        DebugVisualizer.highlightBlock(blockLocation, 5, NamedTextColor.GREEN);
        BlockBreakerAction action = new BlockBreakerAction(block);
        persona.getActionController().schedule(action);
        return action.getFuture().thenApply(status -> status == ActionStatus.SUCCESS);
    }
}