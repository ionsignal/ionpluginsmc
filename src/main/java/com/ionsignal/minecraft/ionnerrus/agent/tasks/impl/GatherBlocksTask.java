package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.AccessibleBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.*;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationResult;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class GatherBlocksTask implements Task {
    private NerrusAgent agent;
    private Executor mainThreadExecutor;
    private int gatheredCount;
    private final Set<Location> attemptedLocations = new HashSet<>();
    private final Set<Material> materials;
    private final int requiredCount;
    private final int searchRadius;
    private final Logger logger;
    private volatile boolean cancelled = false;

    public enum GatherResult {
        SUCCESS, NO_BLOCKS_FOUND, FAILED_TO_COLLECT
    }

    public GatherBlocksTask(Set<Material> materials, int requiredCount, int searchRadius) {
        this.materials = materials;
        this.requiredCount = requiredCount;
        this.searchRadius = searchRadius;
        this.logger = IonNerrus.getInstance().getLogger();
        this.mainThreadExecutor = IonNerrus.getInstance().getMainThreadExecutor();
    }

    @Override
    public void cancel() {
        this.cancelled = true;
        if (agent != null && agent.getPersona().isSpawned()) {
            agent.getPersona().getNavigator().cancelNavigation(NavigationResult.CANCELLED);
        }
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        this.agent = agent;
        this.gatheredCount = agent.getBlackboard().getInt(BlackboardKeys.GATHERED_COUNT, 0);
        this.cancelled = false;
        return gatherAndLoop().whenComplete((res, ex) -> {
            agent.getBlackboard().put(BlackboardKeys.GATHERED_COUNT, gatheredCount);
        });
    }

    private CompletableFuture<Void> gatherAndLoop() {
        if (cancelled || gatheredCount >= requiredCount) {
            agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCKS_RESULT, GatherResult.SUCCESS);
            return CompletableFuture.completedFuture(null);
        }
        return new FindAccessibleBlockSkill(materials, searchRadius, 3, 6, attemptedLocations)
                .execute(agent)
                .thenComposeAsync(resultOpt -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (resultOpt.isEmpty()) {
                        // No accessible blocks found in the entire search radius.
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCKS_RESULT, GatherResult.NO_BLOCKS_FOUND);
                        return CompletableFuture.completedFuture(null);
                    }
                    AccessibleBlockResult result = resultOpt.get();
                    // Add the found block to the ignore list for subsequent searches.
                    attemptedLocations.add(result.blockLocation());
                    // The logic to find a standing spot is now encapsulated in the skill.
                    // We can proceed directly to navigating and breaking.
                    return navigateToAndBreak(result.standingLocation(), result.blockLocation());
                }, mainThreadExecutor);
    }

    private CompletableFuture<Void> navigateToAndBreak(Location standLocation, Location blockToBreak) {
        return new NavigateToLocationSkill(standLocation, blockToBreak).execute(agent)
                .thenCompose(navSuccess -> {
                    if (cancelled || !navSuccess) {
                        if (!cancelled) {
                            logger.info("Could not navigate to the spot to break the block. Finding next one.");
                        }
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCKS_RESULT, GatherResult.FAILED_TO_COLLECT);
                        return CompletableFuture.completedFuture(false);
                    }
                    return new BreakBlockSkill(blockToBreak).execute(agent);
                })
                .thenCompose(breakSuccess -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (!breakSuccess) {
                        logger.warning("Could not break the block. Finding next one.");
                        return gatherAndLoop();
                    }
                    return collectNearbyItem(blockToBreak);
                });
    }

    private CompletableFuture<Void> collectNearbyItem(Location brokenBlockLocation) {
        return CompletableFuture.runAsync(() -> {
            // waiting for drop
        }, CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS))
                .thenComposeAsync(v -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(false);
                    }
                    Item targetItem = findDroppedItem(brokenBlockLocation);
                    if (targetItem == null || targetItem.isDead()) {
                        logger.warning("Could not find dropped item. Assuming it was collected or despawned. Moving on.");
                        // This is a success from the perspective of breaking the block, so increment count.
                        return CompletableFuture.completedFuture(true);
                    }
                    return new CollectItemSkill(targetItem).execute(agent);
                }, mainThreadExecutor)
                .thenCompose(collectSuccess -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (collectSuccess) {
                        gatheredCount++;
                    } else {
                        logger.warning("Failed to navigate to pick up item. Still counting block as gathered.");
                        gatheredCount++; // Still count it, as the block is broken. Pickup is best-effort.
                    }
                    return gatherAndLoop();
                });
    }

    private Item findDroppedItem(Location blockLocation) {
        World world = blockLocation.getWorld();
        if (world == null)
            return null;
        Collection<Entity> nearby = world.getNearbyEntities(blockLocation.clone().add(0.5, 0.5, 0.5), 2.0, 2.0, 2.0);
        return nearby.stream()
                .filter(e -> e instanceof Item && !e.isDead())
                .map(e -> (Item) e)
                .filter(item -> materials.contains(item.getItemStack().getType()))
                .min(Comparator.comparingDouble(i -> i.getLocation().distanceSquared(blockLocation)))
                .orElse(null);
    }
}