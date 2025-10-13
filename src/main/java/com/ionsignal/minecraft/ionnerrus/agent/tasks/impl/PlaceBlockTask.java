package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
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

    private final Material material;

    public PlaceBlockTask(Material material) {
        this.material = material;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        return new PlaceBlockSkill(material).execute(agent)
                .thenAccept(placedLocationOpt -> {
                    placedLocationOpt.ifPresent(
                            location -> agent.getBlackboard().put(BlackboardKeys.CRAFTING_TABLE_LOCATION, location));
                    // If it fails, do nothing. The calling goal is responsible for checking the blackboard.
                });
    }

    @Override
    public void cancel() {
        // The underlying skill is short-lived and its future is handled by the chain.
    }
}