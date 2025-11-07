package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.CraftItemGoal.BlockPlacementResult;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.PlaceBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Material;

import java.util.concurrent.CompletableFuture;

/**
 * A reusable task that wraps the PlaceBlockSkill.
 * On success, it places the new block's location on the blackboard.
 */
public class PlaceBlockTask implements Task {
    private final Object contextToken;
    private final Material material;

    public PlaceBlockTask(Material material, Object contextToken) {
        this.contextToken = contextToken;
        this.material = material;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        return new PlaceBlockSkill(material).execute(agent)
                .thenAccept(placedLocationOpt -> {
                    if (placedLocationOpt.isPresent()) {
                        agent.postMessage(contextToken, BlockPlacementResult.success(placedLocationOpt.get()));
                    } else {
                        agent.postMessage(contextToken, BlockPlacementResult.failure());
                    }
                });
    }

    @Override
    public void cancel() {
        // The underlying skill is short-lived and its future is handled by the chain.
    }
}