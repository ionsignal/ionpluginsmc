package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindCollectableBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.PlaceBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.FindCollectableBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A task to ensure a crafting table is available. It first searches for a nearby
 * table. If none is found, it attempts to place one from the agent's inventory.
 * On success, it places the table's location on the blackboard.
 */
public class EnsureCraftingStationTask implements Task {
    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        // Step 1: Find an existing table nearby.
        return new FindCollectableBlockSkill(Set.of(Material.CRAFTING_TABLE), 20, new HashSet<>())
                .execute(agent)
                .thenCompose(findResult -> {
                    if (findResult.status() == FindCollectableBlockResult.Status.SUCCESS) {
                        // Found a table, put its location on the blackboard and we're done.
                        Location tableLocation = findResult.optimalTarget().get().blockLocation();
                        agent.getBlackboard().put(BlackboardKeys.CRAFTING_TABLE_LOCATION, tableLocation);
                        return CompletableFuture.completedFuture(null);
                    }
                    // Step 2: If not found, try to place one from inventory.
                    agent.speak("I will need to place a crafting table.");
                    return new PlaceBlockSkill(Material.CRAFTING_TABLE).execute(agent)
                            .thenAccept(placedLocationOpt -> {
                                if (placedLocationOpt.isPresent()) {
                                    agent.getBlackboard().put(BlackboardKeys.CRAFTING_TABLE_LOCATION, placedLocationOpt.get());
                                } else {
                                    // Failure: No table found and couldn't place one.
                                    // The goal will see the blackboard key is missing and handle it.
                                    agent.speak("I could not place a crafting table.");
                                }
                            });
                });
    }

    @Override
    public void cancel() {
        // Skills are short-lived; cancellation is handled by not continuing the chain.
    }
}