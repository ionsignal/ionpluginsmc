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
 */
public class CollectItemSkill implements Skill<Boolean> {
    private final Item targetItem;

    public CollectItemSkill(Item targetItem) {
        this.targetItem = targetItem;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        if (targetItem == null || targetItem.isDead()) {
            return CompletableFuture.completedFuture(true);
        }
        return new FindNearbyReachableSpotSkill(targetItem.getLocation(), 2, 1).execute(agent)
                .thenCompose(standingSpotOpt -> {
                    if (standingSpotOpt.isEmpty()) {
                        // No pathable location near the item was found.
                        return CompletableFuture.completedFuture(false);
                    }
                    Location destination = standingSpotOpt.get();
                    Location lookAt = targetItem.getLocation();
                    return new NavigateToLocationSkill(destination, lookAt).execute(agent);
                })
                .thenCompose(navSuccess -> {
                    if (!navSuccess) {
                        // This can happen if the item is truly unreachable or navigation is interrupted.
                        return CompletableFuture.completedFuture(false);
                    }
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