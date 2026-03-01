package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common;

import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A skill that counts the total number of items of specified materials
 * in the agent's inventory.
 */
public class CountItemsSkill implements Skill<Map<Material, Integer>> {
    private final Set<Material> materials;

    public CountItemsSkill(Set<Material> materials) {
        this.materials = materials;
    }

    @Override
    @SuppressWarnings("null")
    public CompletableFuture<Map<Material, Integer>> execute(NerrusAgent agent, ExecutionToken token) {
        PlayerInventory inventory = agent.getPersona().getInventory();
        Map<Material, Integer> counts = new HashMap<>();
        if (inventory == null) {
            return CompletableFuture.completedFuture(counts);
        }
        for (ItemStack item : inventory.getContents()) {
            if (item != null && materials.contains(item.getType())) {
                counts.merge(item.getType(), item.getAmount(), Integer::sum);
            }
        }
        return CompletableFuture.completedFuture(counts);
    }
}