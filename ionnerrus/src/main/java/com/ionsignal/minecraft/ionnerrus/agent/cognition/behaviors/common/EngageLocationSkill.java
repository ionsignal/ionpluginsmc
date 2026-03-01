package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import com.ionsignal.minecraft.ionnerrus.util.StaticEntityProxy;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;

/**
 * A skill that uses the 'Engage' capability (Direct Steering) to move towards a static location.
 * Useful for micro-adjustments where A* pathfinding is overkill or fails (e.g. moving towards a
 * solid block).
 */
public class EngageLocationSkill implements Skill<MovementResult> {
    private final Location target;
    private final double stopDistanceSquared;

    public EngageLocationSkill(Location target, double stopDistanceSquared) {
        this.target = target;
        this.stopDistanceSquared = stopDistanceSquared;
    }

    @Override
    public CompletableFuture<MovementResult> execute(NerrusAgent agent, ExecutionToken token) {
        return CompletableFuture.supplyAsync(() -> {
            if (!agent.getPersona().isSpawned() || !token.isActive()) {
                return CompletableFuture.completedFuture(MovementResult.CANCELLED);
            }
            // Create the proxy holder
            Entity proxyTarget = StaticEntityProxy.create(target);
            // Execute the engage capability
            // This will drive the agent directly towards the target until distanceSquared < stopDistanceSquared
            return agent.getPersona().getPhysicalBody().movement()
                    .engage(proxyTarget, stopDistanceSquared, token);
        }, IonNerrus.getInstance().getMainThreadExecutor())
                .thenCompose(future -> future);
    }
}