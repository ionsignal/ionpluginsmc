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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class GatherBlockTask implements Task {
    private NerrusAgent agent;
    private Executor mainThreadExecutor;
    private final Set<Location> attemptedLocations;
    private final Set<Material> materials;
    private final int searchRadius;
    private final Logger logger;
    private volatile boolean cancelled = false;

    public enum GatherResult {
        SUCCESS, NO_BLOCKS_FOUND, FAILED_TO_COLLECT
    }

    public GatherBlockTask(Set<Material> materials, int searchRadius, Set<Location> attemptedLocations) {
        this.materials = materials;
        this.searchRadius = searchRadius;
        this.attemptedLocations = attemptedLocations;
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
        this.cancelled = false;
        // The task now only performs one gather cycle.
        return gatherSingleBlock();
    }

    private CompletableFuture<Void> gatherSingleBlock() {
        if (cancelled) {
            return CompletableFuture.completedFuture(null);
        }
        return new FindAccessibleBlockSkill(materials, searchRadius, 3, 6, attemptedLocations)
                .execute(agent)
                .thenComposeAsync(resultOpt -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (resultOpt.isEmpty()) {
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.NO_BLOCKS_FOUND);
                        return CompletableFuture.completedFuture(null);
                    }
                    AccessibleBlockResult result = resultOpt.get();
                    attemptedLocations.add(result.blockLocation());
                    // Chain to thenAccept to correctly terminate the future chain as void
                    return navigateToAndBreak(result.standingLocation(), result.blockLocation())
                            .thenAccept(success -> {
                                // The blackboard is updated within the chain. We just need to end the future here.
                                if (!success) {
                                    logger.info("Gather cycle failed for block at " + result.blockLocation());
                                }
                            });
                }, mainThreadExecutor);
    }

    private CompletableFuture<Boolean> navigateToAndBreak(Location standLocation, Location blockToBreak) {
        return new NavigateToLocationSkill(standLocation, blockToBreak).execute(agent)
                .thenCompose(navSuccess -> {
                    if (cancelled || !navSuccess) {
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.FAILED_TO_COLLECT);
                        return CompletableFuture.completedFuture(false);
                    }
                    return new EquipBestToolSkill(blockToBreak.getBlock()).execute(agent);
                })
                .thenCompose(equipSuccess -> {
                    if (cancelled || !equipSuccess) {
                        if (!cancelled) {
                            logger.warning("Could not equip best tool. Finding next block.");
                        }
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.FAILED_TO_COLLECT);
                        return CompletableFuture.completedFuture(false);
                    }
                    return new BreakBlockSkill(blockToBreak).execute(agent);
                })
                .thenCompose(breakSuccess -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(false);
                    }
                    if (!breakSuccess) {
                        logger.warning("Could not break the block. Finding next one.");
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.FAILED_TO_COLLECT);
                        return CompletableFuture.completedFuture(false);
                    }
                    return collectNearbyItem(blockToBreak);
                });
    }

    private CompletableFuture<Boolean> collectNearbyItem(Location brokenBlockLocation) {
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
                        return CompletableFuture.completedFuture(true);
                    }
                    return new CollectItemSkill(targetItem).execute(agent);
                }, mainThreadExecutor)
                .thenApply(collectSuccess -> {
                    if (cancelled) {
                        return false;
                    }
                    if (collectSuccess) {
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.SUCCESS);
                    } else {
                        logger.warning("Failed to navigate to pick up item. Still counting as a partial success.");
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.FAILED_TO_COLLECT);
                    }
                    return collectSuccess;
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