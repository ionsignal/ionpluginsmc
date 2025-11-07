package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindNearestBiomeSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindNearbyReachableSpotSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Location;
import org.bukkit.block.Biome;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class FindBiomeTask implements Task {
    /**
     * Message sent by FindBiomeTask after completing the search.
     */
    public static record BiomeSearchResult(Status status, Optional<Location> location) {
        public enum Status {
            SUCCESS, NOT_FOUND
        }

        public static BiomeSearchResult success(Location location) {
            return new BiomeSearchResult(Status.SUCCESS, Optional.of(location));
        }

        public static BiomeSearchResult notFound() {
            return new BiomeSearchResult(Status.NOT_FOUND, Optional.empty());
        }
    }

    private NerrusAgent agent;
    private final Logger logger;
    private final Set<Biome> biomesToFind;
    private final int searchRadius;
    private final Object contextToken;
    private volatile boolean cancelled = false;

    public FindBiomeTask(Set<Biome> biomesToFind, int searchRadius, Object contextToken) {
        this.biomesToFind = biomesToFind;
        this.searchRadius = searchRadius;
        this.contextToken = contextToken; // ADDED: Store context token
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
                agent.postMessage(contextToken, BiomeSearchResult.notFound());
                logger.info("Agent couldn't find the specified biome.");
            }
            return CompletableFuture.completedFuture(null);
        }, IonNerrus.getInstance().getMainThreadExecutor());
    }

    private CompletableFuture<Void> processSingleBlock(Location blockLocation, String biomeNames) {
        return new FindNearbyReachableSpotSkill(blockLocation, 32).execute(agent)
                .thenCompose(standLocationOpt -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (standLocationOpt.isPresent()) {
                        Location standLocation = standLocationOpt.get();
                        agent.postMessage(contextToken, BiomeSearchResult.success(standLocation));
                        logger.info("Agent found location nearby at " + standLocation.toString());
                    } else {
                        agent.postMessage(contextToken, BiomeSearchResult.notFound());
                        logger.info("Could not find a good stand location at biome.");
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }
}