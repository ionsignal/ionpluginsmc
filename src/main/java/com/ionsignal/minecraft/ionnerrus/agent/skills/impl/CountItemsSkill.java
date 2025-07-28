package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A skill that counts the total number of items of specified materials
 * in the agent's inventory.
 */
public class CountItemsSkill implements Skill<Integer> {

    private final Set<Material> materials;

    public CountItemsSkill(Set<Material> materials) {
        this.materials = materials;
    }

    @Override
    public CompletableFuture<Integer> execute(NerrusAgent agent) {
        PlayerInventory inventory = agent.getPersona().getInventory();
        if (inventory == null) {
            return CompletableFuture.completedFuture(0);
        }

        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && materials.contains(item.getType())) {
                count += item.getAmount();
            }
        }
        return CompletableFuture.completedFuture(count);
    }
}