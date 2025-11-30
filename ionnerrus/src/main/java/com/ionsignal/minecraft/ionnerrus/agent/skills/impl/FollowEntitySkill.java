package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;

/**
 * A skill wrapper for the PhysicalBody's follow capability.
 */
public class FollowEntitySkill implements Skill<MovementResult> {
    private final Entity target;
    private final double followDistance;
    private final double stopDistance;

    public FollowEntitySkill(Entity target, double followDistance, double stopDistance) {
        this.target = target;
        this.followDistance = followDistance;
        this.stopDistance = stopDistance;
    }

    @Override
    public CompletableFuture<MovementResult> execute(NerrusAgent agent, ExecutionToken token) {
        // Pass the token to the physical body to bind the lifecycle of the follow operation
        return agent.getPersona().getPhysicalBody().movement().follow(target, followDistance, stopDistance, token);
    }
}