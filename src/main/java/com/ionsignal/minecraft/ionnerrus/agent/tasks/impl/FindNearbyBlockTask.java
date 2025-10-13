package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindCollectableBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.FindCollectableBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Material;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A reusable task to find the nearest instance of a specific block material within a given radius.
 * On success, it places the block's location on the blackboard.
 */
public class FindNearbyBlockTask implements Task {

    private final Material material;
    private final int searchRadius;

    public FindNearbyBlockTask(Material material, int searchRadius) {
        this.material = material;
        this.searchRadius = searchRadius;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        return new FindCollectableBlockSkill(Set.of(material), searchRadius, new HashSet<>())
                .execute(agent)
                .thenAccept(findResult -> {
                    if (findResult.status() == FindCollectableBlockResult.Status.SUCCESS) {
                        // We only care about the block's location, not the path or standing spot.
                        agent.getBlackboard().put(BlackboardKeys.CRAFTING_TABLE_LOCATION,
                                findResult.optimalTarget().get().blockLocation());
                    }
                    // If not found, do nothing. The calling goal is responsible for checking the blackboard.
                });
    }

    @Override
    public void cancel() {
        // The underlying skill is short-lived and its future is handled by the chain.
    }
}