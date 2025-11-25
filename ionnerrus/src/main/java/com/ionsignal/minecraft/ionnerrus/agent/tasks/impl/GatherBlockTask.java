package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GatherBlockGoal.GatherResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GatherBlockGoal.GatherAttemptResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.CollectableBlock;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.BreakBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CollectItemSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.EquipBestToolSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindCollectableBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.NavigateToLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.BreakBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.NavigateToLocationResult;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.PathNode;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult.MovementType;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.minecraft.core.BlockPos;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

public class GatherBlockTask implements Task {
    private static final int MAX_RECURSION_DEPTH = 3;
    public static final boolean VISUALIZE_PATH = true;

    private NerrusAgent agent;

    private final Logger logger;
    private final Object contextToken;
    private final Executor mainThreadExecutor;
    private final Set<Location> attemptedLocations;
    private final Set<UUID> unreachableItems = new HashSet<>();
    private final Set<Material> targetBlocks;
    private final Set<Material> expectedDrops;
    private final int searchRadius;

    private volatile boolean cancelled = false;

    public GatherBlockTask(Set<Material> targetBlocks, Set<Material> expectedDrops, int searchRadius,
            Set<Location> attemptedLocations,
            Object contextToken) {
        this.targetBlocks = targetBlocks;
        this.expectedDrops = (expectedDrops == null || expectedDrops.isEmpty()) ? targetBlocks : expectedDrops;
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
            agent.getPersona().getPhysicalBody().actions().cancelAction();
            agent.getPersona().getPhysicalBody().movement().stop();
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
        return new FindCollectableBlockSkill(targetBlocks, searchRadius, attemptedLocations)
                .execute(agent)
                .thenComposeAsync(result -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
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
                            agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.NO_BLOCKS_IN_RANGE));
                            return CompletableFuture.completedFuture(null);
                        case NO_STANDPOINTS_FOUND:
                        case NO_PATH_FOUND:
                            agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.NO_REACHABLE_BLOCKS_IN_RANGE));
                            return CompletableFuture.completedFuture(null);
                        default:
                            return CompletableFuture.completedFuture(null);
                    }
                }, mainThreadExecutor);
    }

    // Added: Overload to support initial call with depth 0
    private CompletableFuture<Boolean> navigateToAndBreak(CollectableBlock target) {
        return navigateToAndBreak(target, 0);
    }

    // Modified: Added recursionDepth parameter and logic to handle OBSTRUCTED result
    private CompletableFuture<Boolean> navigateToAndBreak(CollectableBlock target, int recursionDepth) {
        if (VISUALIZE_PATH) {
            DebugVisualizer.displayPath(target.pathToStand(), 40);
        }
        NavigateToLocationSkill navSkill = new NavigateToLocationSkill(target.pathToStand(), target.blockLocation());
        return navSkill.execute(agent)
                .thenCompose(navResult -> {
                    if (cancelled)
                        return CompletableFuture.completedFuture(false);
                    if (navResult != NavigateToLocationResult.SUCCESS) {
                        // Only report failure on top-level to avoid spamming messages during recursion
                        if (recursionDepth == 0) {
                            agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                        }
                        return CompletableFuture.completedFuture(false);
                    }
                    return new EquipBestToolSkill(target.blockLocation().getBlock()).execute(agent);
                })
                .thenCompose(equipSuccess -> {
                    if (cancelled)
                        return CompletableFuture.completedFuture(false);
                    if (!equipSuccess) {
                        if (recursionDepth == 0) {
                            agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                        }
                        return CompletableFuture.completedFuture(false);
                    }
                    return new BreakBlockSkill(target.blockLocation()).execute(agent)
                            .thenCompose(breakResult -> {
                                if (cancelled)
                                    return CompletableFuture.completedFuture(false);
                                // Handle Success
                                // Updated to use Record accessors
                                boolean wasSuccessful = breakResult.status() == BreakBlockResult.Status.SUCCESS
                                        || breakResult.status() == BreakBlockResult.Status.ALREADY_BROKEN;
                                if (wasSuccessful) {
                                    if (recursionDepth > 0) {
                                        // If we were clearing an obstruction, return true so the parent can retry the main target
                                        return CompletableFuture.completedFuture(true);
                                    } else {
                                        // If this was the main target, proceed to collect item
                                        return collectNearbyItem(target.blockLocation());
                                    }
                                }
                                // Handle Obstruction (Recursive Clearing)
                                if (breakResult.status() == BreakBlockResult.Status.OBSTRUCTED
                                        && recursionDepth < MAX_RECURSION_DEPTH
                                        && breakResult.obstruction().isPresent()) {
                                    Location obstructionLoc = breakResult.obstruction().get();
                                    logger.info(String.format("[GatherBlockTask] Target at %s obstructed by %s. Recursing (Depth: %d)",
                                            target.blockLocation().toVector(), obstructionLoc.toVector(), recursionDepth + 1));
                                    // Create a "stand here" path since we are already close enough to see the obstruction
                                    Location agentLoc = agent.getPersona().getLocation();
                                    BlockPos agentPos = new BlockPos(agentLoc.getBlockX(), agentLoc.getBlockY(), agentLoc.getBlockZ());
                                    PathNode startNode = new PathNode(agentPos, MovementType.WALK, 0.5);
                                    Path currentPath = new Path(List.of(startNode), agentLoc.getWorld());
                                    CollectableBlock obstructionTarget = new CollectableBlock(
                                            obstructionLoc,
                                            target.standingLocation(),
                                            currentPath);
                                    // Recurse to break the obstruction
                                    return navigateToAndBreak(obstructionTarget, recursionDepth + 1).thenCompose(cleared -> {
                                        if (cancelled)
                                            return CompletableFuture.completedFuture(false);
                                        if (cleared) {
                                            // Obstruction cleared, retry original target
                                            return navigateToAndBreak(target, recursionDepth);
                                        } else {
                                            // Failed to clear obstruction
                                            if (recursionDepth == 0) {
                                                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                                            }
                                            return CompletableFuture.completedFuture(false);
                                        }
                                    });
                                }
                                // 3. Handle Failure
                                if (recursionDepth == 0) {
                                    logger.warning("Failed to break block at " + target.blockLocation().toVector() + " for reason: "
                                            + breakResult.status());
                                    agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                                }
                                return CompletableFuture.completedFuture(false);
                            });
                });
    }

    private void clearLookTarget() {
        if (agent != null && agent.getPersona().isSpawned() && agent.getPersona().getPersonaEntity() != null) {
            agent.getPersona().getPhysicalBody().orientation().clearLookTarget();
        }
    }

    private CompletableFuture<Boolean> collectNearbyItem(Location brokenBlockLocation) {
        final CompletableFuture<Item> itemFuture = new CompletableFuture<>();
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (cancelled) {
                    itemFuture.complete(null);
                    this.cancel();
                    return;
                }
                Item foundItem = findDroppedItem(brokenBlockLocation);
                if (foundItem != null) {
                    itemFuture.complete(foundItem);
                    this.cancel();
                } else if (ticks++ > 20) {
                    logger.warning("[GatherBlockTask] Failed to find dropped item in 1 second timeout.");
                    itemFuture.complete(null);
                    this.cancel();
                }
            }
        }.runTaskTimer(IonNerrus.getInstance(), 2L, 2L);
        return itemFuture.thenComposeAsync(targetItem -> {
            if (cancelled)
                return CompletableFuture.completedFuture(false);
            if (targetItem == null || targetItem.isDead()) {
                logger.warning("Could not find dropped item after breaking block. This attempt failed.");
                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                return CompletableFuture.completedFuture(false);
            }
            return new CollectItemSkill(targetItem).execute(agent)
                    .thenApply(collectResult -> {
                        boolean wasSuccessful = false;
                        switch (collectResult.status()) {
                            case SUCCESS:
                            case ITEM_GONE:
                                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.SUCCESS));
                                wasSuccessful = true;
                                break;
                            case NAVIGATION_FAILED:
                            case NO_PATH_FOUND:
                            case NO_STANDPOINTS_FOUND:
                                logger.warning("Could not collect item because it is unreachable due to " + collectResult.status()
                                        + ". Blacklisting for this task.");
                                unreachableItems.add(targetItem.getUniqueId());
                                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                                break;
                            case PICKUP_TIMEOUT:
                                logger.warning("Failed to collect item due to timeout.");
                                agent.postMessage(contextToken, new GatherAttemptResult(GatherResult.FAILED_TO_COLLECT));
                                break;
                            case CANCELLED:
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
                .filter(item -> !unreachableItems.contains(item.getUniqueId()))
                .filter(item -> expectedDrops.contains(item.getItemStack().getType()))
                .min(Comparator.comparingDouble(i -> i.getLocation().distanceSquared(blockLocation)))
                .orElse(null);
    }
}