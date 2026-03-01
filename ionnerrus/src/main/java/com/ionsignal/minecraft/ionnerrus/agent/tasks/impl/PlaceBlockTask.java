package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
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
    public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
        return new PlaceBlockSkill(material).execute(agent, token)
                .thenAccept(placedLocationOpt -> {
                    // no-op
                });
    }
}