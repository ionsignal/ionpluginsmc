package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindNearestBiomeSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindStandingLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Location;
import org.bukkit.block.Biome;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class FindBiomeTask implements Task {
    private NerrusAgent agent;
    private final Logger logger;
    private final Set<Biome> biomesToFind;
    private final int searchRadius;
    private volatile boolean cancelled = false;

    public FindBiomeTask(Set<Biome> biomesToFind, int searchRadius) {
        this.biomesToFind = biomesToFind;
        this.searchRadius = searchRadius;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        this.agent = agent;
        this.cancelled = false;
        FindNearestBiomeSkill findSkill = new FindNearestBiomeSkill(biomesToFind, searchRadius);
        String biomeNames = biomesToFind.stream()
                .map(b -> b.getKey().getKey().replace('_', ' '))
                .collect(Collectors.joining(", "));
        return findSkill.execute(agent).thenComposeAsync(location -> {
            if (cancelled) {
                return CompletableFuture.completedFuture(null);
            }
            if (location != null) {
                return processSingleBlock(location, biomeNames);
            } else {
                agent.getBlackboard().remove(BlackboardKeys.TARGET_LOCATION);
                agent.getBlackboard().put(BlackboardKeys.ISSUE, biomeNames + " not within " + searchRadius);
                logger.info("Agent couldn't find the specified biome.");
            }
            return CompletableFuture.completedFuture(null);
        }, IonNerrus.getInstance().getMainThreadExecutor());
    }

    private CompletableFuture<Void> processSingleBlock(Location blockLocation, String biomeNames) {
        return new FindStandingLocationSkill(blockLocation).execute(agent)
                .thenCompose(standLocation -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (standLocation != null) {
                        agent.getBlackboard().put(BlackboardKeys.TARGET_LOCATION, standLocation);
                        logger.info("Agent found location nearby at " + standLocation.toString());
                    } else {
                        agent.getBlackboard().remove(BlackboardKeys.TARGET_LOCATION);
                        agent.getBlackboard().put(BlackboardKeys.ISSUE,
                                "Found a " + biomeNames + " biome, but could not find a reachable spot within it.");
                        logger.info("Could not find a good stand location at biome.");
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }
}