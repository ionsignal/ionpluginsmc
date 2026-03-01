package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A skill to find a target LivingEntity (Player or Persona) by name.
 */
public class FindTargetEntitySkill implements Skill<Optional<LivingEntity>> {
    private final String targetName;

    public FindTargetEntitySkill(String targetName) {
        this.targetName = targetName;
    }

    @Override
    public CompletableFuture<Optional<LivingEntity>> execute(NerrusAgent agent, ExecutionToken token) {
        // 1. Check token immediately
        if (!token.isActive()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            // 2. Run logic synchronously
            Player player = Bukkit.getPlayerExact(targetName);
            if (player != null && player.isOnline()) {
                return CompletableFuture.completedFuture(Optional.of(player));
            }
            // If not a player, check for another Nerrus agent
            AgentService agentService = IonNerrus.getInstance().getAgentService();
            NerrusAgent targetAgent = agentService.findAgentByName(targetName);
            if (targetAgent != null && targetAgent.getPersona().isSpawned()) {
                if (targetAgent.getPersona().getEntity() instanceof LivingEntity livingEntity) {
                    return CompletableFuture.completedFuture(Optional.of(livingEntity));
                }
            }
            return CompletableFuture.completedFuture(Optional.empty());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}