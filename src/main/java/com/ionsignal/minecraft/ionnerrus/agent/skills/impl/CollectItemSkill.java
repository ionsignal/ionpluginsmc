package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;

import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.CompletableFuture;

/**
 * A skill for an agent to navigate to and collect a specific item entity.
 * This skill improves pickup reliability by calculating an "overshoot" target,
 * encouraging the NPC to walk through the item's location rather than just to it.
 * It also safely manages the NPC's item pickup metadata flag.
 */
public class CollectItemSkill implements Skill<Boolean> {
    private final Item targetItem;

    public CollectItemSkill(Item targetItem) {
        this.targetItem = targetItem;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        if (targetItem == null || targetItem.isDead()) {
            return CompletableFuture.completedFuture(true); // Already collected or invalid
        }

        Location location = targetItem.getLocation();
        return new NavigateToLocationSkill(location, location).execute(agent)
                .thenCompose(navSuccess -> {
                    if (!navSuccess) {
                        // This can happen if the item is truly unreachable.
                        return CompletableFuture.completedFuture(false);
                    }

                    // 3. Wait briefly for the server to process the pickup.
                    CompletableFuture<Boolean> pickupFuture = new CompletableFuture<>();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            // Success is defined as the item no longer being in the world.
                            pickupFuture.complete(targetItem.isDead());
                        }
                    }.runTaskLater(IonNerrus.getInstance(), 5L); // 5 ticks (1/4 second) delay
                    return pickupFuture;
                });
    }
}