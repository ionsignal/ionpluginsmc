package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;

/**
 * A skill that uses the 'Engage' capability (Direct Steering) to move towards a dynamic entity.
 * This bypasses A* pathfinding and WorldSnapshots, making it ideal for "Fast Mode" collection
 * of nearby items.
 */
public class EngageEntitySkill implements Skill<MovementResult> {
    private final Entity target;
    private final double stopDistanceSquared;

    public EngageEntitySkill(Entity target, double stopDistanceSquared) {
        this.target = target;
        this.stopDistanceSquared = stopDistanceSquared;
    }

    @Override
    public CompletableFuture<MovementResult> execute(NerrusAgent agent, ExecutionToken token) {
        return CompletableFuture.supplyAsync(() -> {
            if (!agent.getPersona().isSpawned() || !token.isActive()) {
                return CompletableFuture.completedFuture(MovementResult.CANCELLED);
            }
            // Delegate directly to the physical body's engage capability
            return agent.getPersona().getPhysicalBody().movement()
                    .engage(target, stopDistanceSquared, token);
        }, IonNerrus.getInstance().getMainThreadExecutor())
                .thenCompose(f -> f);
    }
}