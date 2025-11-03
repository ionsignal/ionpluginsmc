package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;

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
    public CompletableFuture<Optional<LivingEntity>> execute(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            // Check for an online player with the exact name.
            Player player = Bukkit.getPlayerExact(targetName);
            if (player != null && player.isOnline()) {
                return Optional.of(player);
            }
            // If not a player, check for another Nerrus agent.
            AgentService agentService = IonNerrus.getInstance().getAgentService();
            NerrusAgent targetAgent = agentService.findAgentByName(targetName);
            if (targetAgent != null && targetAgent.getPersona().isSpawned()) {
                if (targetAgent.getPersona().getEntity() instanceof LivingEntity livingEntity) {
                    return Optional.of(livingEntity);
                }
            }
            // Target not found.
            return Optional.empty();
        });
    }
}