package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.CraftItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindCollectableBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.FindCollectableBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Location;
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
    private final Object contextToken;

    public FindNearbyBlockTask(Material material, int searchRadius, Object contextToken) {
        this.material = material;
        this.searchRadius = searchRadius;
        this.contextToken = contextToken;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        return new FindCollectableBlockSkill(Set.of(material), searchRadius, new HashSet<>())
                .execute(agent)
                .thenAcceptAsync(findResult -> {
                    if (findResult.status() == FindCollectableBlockResult.Status.SUCCESS) {
                        Location location = findResult.optimalTarget().get().blockLocation();
                        agent.postMessage(contextToken, CraftItemGoal.TableSearchResult.found(location));
                    } else {
                        agent.postMessage(contextToken, CraftItemGoal.TableSearchResult.notFound());
                    }
                }, IonNerrus.getInstance().getMainThreadExecutor());
    }

    @Override
    public void cancel() {
        // The underlying skill is short-lived and its future is handled by the chain.
    }
}