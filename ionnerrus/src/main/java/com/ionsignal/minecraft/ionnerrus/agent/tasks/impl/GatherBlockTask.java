package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GatherGoal.GatherResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GatherGoal.GatherAttemptResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.CollectableBlock;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.BreakBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CollectItemSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.EquipBestToolSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindCollectableBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.NavigateToLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.BreakBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.NavigateToLocationResult;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.EngageResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

public class GatherBlockTask implements Task {
    public static final boolean VISUALIZE_PATH = true;

    private final Object contextToken;
    private final Executor mainThreadExecutor;
    private final Set<Location> attemptedLocations;
    private final Set<UUID> unreachableItems = new HashSet<>();
    private final Set<Material> materials;
    private final int searchRadius;
    private final Logger logger;
    private volatile boolean cancelled = false;
    private NerrusAgent agent;

    public GatherBlockTask(Set<Material> materials, int searchRadius, Set<Location> attemptedLocations, Object contextToken) {
        this.materials = materials;
        this.searchRadius = searchRadius;
        this.attemptedLocations = attemptedLocations;
        this.contextToken = contextToken;
        this.logger = IonNerrus.getInstance().getLogger();
        this.mainThreadExecutor = IonNerrus.getInstance().getMainThreadExecutor();
    }

    @Override
    public void cancel() {
        this.cancelled = true;
        if (agent != null && agent.getPersona().isSpawned()) {
            agent.getPersona().getNavigator().cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
        }
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        this.agent = agent;
        this.cancelled = false;
        CompletableFuture<Void> taskFuture = gatherSingleBlock();
        taskFuture.whenComplete((result, throwable) -> {
            clearLookTarget();
        });
        return taskFuture;
    }

    private CompletableFuture<Void> gatherSingleBlock() {
        if (cancelled) {
            return CompletableFuture.completedFuture(null);
        }
        return new FindCollectableBlockSkill(materials, searchRadius, attemptedLocations)
                .execute(agent)
                .thenComposeAsync(result -> {
                    // Early exit on cancellation without posting message
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    // Use switch on the new rich result status and post messages instead of blackboard
                    switch (result.status()) {
                        case SUCCESS:
                            CollectableBlock target = result.optimalTarget().get();
                            attemptedLocations.add(target.blockLocation());
                            return navigateToAndBreak(target).thenAccept(success -> {
                                if (!success) {
                                    logger.info("Gather cycle failed for block at " + target.blockLocation());
                                }
                            });
                        case NO_TARGETS_FOUND:
                            // Post message instead of blackboard write
                            agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.NO_BLOCKS_IN_RANGE));
                            return CompletableFuture.completedFuture(null);
                        case NO_STANDPOINTS_FOUND:
                        case NO_PATH_FOUND:
                            // Post message instead of blackboard write
                            agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.NO_REACHABLE_BLOCKS_IN_RANGE));
                            return CompletableFuture.completedFuture(null);
                        default:
                            // Should be unreachable.
                            return CompletableFuture.completedFuture(null);
                    }
                }, mainThreadExecutor);
    }

    private CompletableFuture<Boolean> navigateToAndBreak(CollectableBlock target) {
        // Use the new skill to navigate while looking at the target block.
        if (VISUALIZE_PATH) {
            DebugVisualizer.displayPath(target.pathToStand(), 40);
        }
        NavigateToLocationSkill navSkill = new NavigateToLocationSkill(target.pathToStand(), target.blockLocation());
        return navSkill.execute(agent)
                .thenCompose(navResult -> {
                    // Split cancellation check from failure messaging (Issue #6)
                    if (cancelled) {
                        // Don't post message if cancelled
                        return CompletableFuture.completedFuture(false);
                    }
                    if (navResult != NavigateToLocationResult.SUCCESS) {
                        // Post message instead of blackboard write
                        agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                        return CompletableFuture.completedFuture(false);
                    }
                    return new EquipBestToolSkill(target.blockLocation().getBlock()).execute(agent);
                })
                .thenCompose(equipSuccess -> {
                    // Split cancellation check from failure messaging (Issue #6)
                    if (cancelled) {
                        // Don't post message if cancelled
                        return CompletableFuture.completedFuture(false);
                    }
                    if (!equipSuccess) {
                        // Post message instead of blackboard write
                        agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                        return CompletableFuture.completedFuture(false);
                    }
                    return new BreakBlockSkill(target.blockLocation()).execute(agent)
                            .thenCompose(breakResult -> {
                                boolean wasSuccessful = breakResult == BreakBlockResult.SUCCESS
                                        || breakResult == BreakBlockResult.ALREADY_BROKEN;
                                // Split cancellation check from failure messaging (Issue #6)
                                if (cancelled) {
                                    // Don't post message if cancelled
                                    return CompletableFuture.completedFuture(false);
                                }
                                if (!wasSuccessful) {
                                    logger.warning("Failed to break block at " + target.blockLocation().toVector() + " for reason: "
                                            + breakResult.name());
                                    // Post message instead of blackboard write
                                    agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                                    return CompletableFuture.completedFuture(false);
                                }
                                return collectNearbyItem(target.blockLocation());
                            });
                });
    }

    private void clearLookTarget() {
        if (agent != null && agent.getPersona().isSpawned() && agent.getPersona().getPersonaEntity() != null) {
            agent.getPersona().getPersonaEntity().getLookControl().stopLooking();
        }
    }

    private CompletableFuture<Boolean> collectNearbyItem(Location brokenBlockLocation) {
        final CompletableFuture<Item> itemFuture = new CompletableFuture<>();
        // Wait for the item to drop.
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (cancelled) {
                    itemFuture.complete(null);
                    this.cancel();
                    return;
                }
                logger.warning("[GatherBlockTask] Looking for dropped item.");
                Item foundItem = findDroppedItem(brokenBlockLocation);
                if (foundItem != null) {
                    itemFuture.complete(foundItem);
                    this.cancel();
                } else if (ticks++ > 20) { // 1 second timeout
                    logger.warning("[GatherBlockTask] Failed to find dropped item in 1 second timeout.");
                    itemFuture.complete(null);
                    this.cancel();
                }
            }
        }.runTaskTimer(IonNerrus.getInstance(), 2L, 2L); // Start after 2 ticks, check every 2 ticks.
        return itemFuture.thenComposeAsync(targetItem -> {
            // Split cancellation check from failure messaging (Issue #6)
            if (cancelled) {
                // Don't post message if cancelled
                return CompletableFuture.completedFuture(false);
            }
            if (targetItem == null || targetItem.isDead()) {
                logger.warning("Could not find dropped item after breaking block. This attempt failed.");
                // Post message instead of blackboard write
                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                return CompletableFuture.completedFuture(false); // This collection attempt was not successful.
            }
            return new CollectItemSkill(targetItem).execute(agent)
                    .thenApply(collectResult -> {
                        boolean wasSuccessful = false;
                        switch (collectResult.status()) {
                            case SUCCESS:
                                // Post message instead of blackboard write
                                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.SUCCESS));
                                wasSuccessful = true;
                                break;
                            case ITEM_GONE: // If it's gone by the time we collect, that's also a success.
                                // Post message instead of blackboard write
                                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.SUCCESS));
                                wasSuccessful = true;
                                break;
                            case NAVIGATION_FAILED:
                            case NO_PATH_FOUND:
                            case NO_STANDPOINTS_FOUND:
                                logger.warning("Could not collect item because it is unreachable due to " + collectResult.status()
                                        + ". Blacklisting for this task.");
                                unreachableItems.add(targetItem.getUniqueId());
                                // Post message instead of blackboard write
                                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                                break;
                            case PICKUP_TIMEOUT:
                                logger.warning("Failed to collect item due to timeout.");
                                // Post message instead of blackboard write
                                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                                break;
                            case CANCELLED:
                                // Do nothing, wasSuccessful remains false.
                                break;
                        }
                        return wasSuccessful;
                    });
        }, mainThreadExecutor);
    }

    private Item findDroppedItem(Location blockLocation) {
        World world = blockLocation.getWorld();
        if (world == null)
            return null;
        Collection<Entity> nearby = world.getNearbyEntities(blockLocation.clone().add(0.5, 0.5, 0.5), 2.0, 2.0, 2.0);
        return nearby.stream()
                .filter(e -> e instanceof Item && !e.isDead())
                .map(e -> (Item) e)
                .filter(item -> !unreachableItems.contains(item.getUniqueId())) // Filter out blacklisted items
                .filter(item -> materials.contains(item.getItemStack().getType()))
                .min(Comparator.comparingDouble(i -> i.getLocation().distanceSquared(blockLocation)))
                .orElse(null);
    }
}