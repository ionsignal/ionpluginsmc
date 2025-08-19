package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.OptimalCollectTarget;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.BreakBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CollectItemSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.EquipBestToolSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindOptimalBlockToCollectSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.NavigateToLocationSkill;
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
        return gatherSingleBlock();
    }

    private CompletableFuture<Void> gatherSingleBlock() {
        if (cancelled) {
            return CompletableFuture.completedFuture(null);
        }

        return new FindOptimalBlockToCollectSkill(materials, searchRadius, attemptedLocations)
                .execute(agent)
                .thenComposeAsync(resultOpt -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (resultOpt.isEmpty()) {
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.NO_BLOCKS_FOUND);
                        return CompletableFuture.completedFuture(null);
                    }
                    OptimalCollectTarget target = resultOpt.get();
                    attemptedLocations.add(target.blockLocation());
                    return navigateToAndBreak(target)
                            .thenAccept(success -> {
                                // The blackboard is updated within the chain. We just need to end the future here.
                                if (!success) {
                                    logger.info("Gather cycle failed for block at " + target.blockLocation());
                                }
                            });
                }, mainThreadExecutor);
    }

    private CompletableFuture<Boolean> navigateToAndBreak(OptimalCollectTarget target) {
        // Use the new skill to navigate while looking at the target block.
        NavigateToLocationSkill navSkill = new NavigateToLocationSkill(target.pathToStand(), target.blockLocation());
        return navSkill.execute(agent)
                .thenCompose(navSuccess -> {
                    if (cancelled || !navSuccess) {
                        clearLookTarget();
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.FAILED_TO_COLLECT);
                        return CompletableFuture.completedFuture(false);
                    }
                    return new EquipBestToolSkill(target.blockLocation().getBlock()).execute(agent);
                })
                .thenCompose(equipSuccess -> {
                    if (cancelled || !equipSuccess) {
                        clearLookTarget();
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.FAILED_TO_COLLECT);
                        return CompletableFuture.completedFuture(false);
                    }
                    // The agent is still looking at the block because the skill didn't clear it.
                    return new BreakBlockSkill(target.blockLocation()).execute(agent);
                })
                .thenCompose(breakSuccess -> {
                    if (cancelled || !breakSuccess) {
                        clearLookTarget();
                        agent.getBlackboard().put(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.FAILED_TO_COLLECT);
                        return CompletableFuture.completedFuture(false);
                    }
                    clearLookTarget();
                    return collectNearbyItem(target.blockLocation());
                });
    }

    private void clearLookTarget() {
        if (agent != null && agent.getPersona().isSpawned() && agent.getPersona().getPersonaEntity() != null) {
            agent.getPersona().getPersonaEntity().getLookControl().stopLooking();
        }
    }

    private CompletableFuture<Boolean> collectNearbyItem(Location brokenBlockLocation) {
        return CompletableFuture.runAsync(() -> {
            //
            // waiting for drop...
            //
            // we need to add logic to track entities that we tried to pickup but couldn't because they fell somewhere un-pathable
            // findDroppedItem will keep returning out of reach dropped items (this isn't a problem for airborne items which are only
            // temporarily un-pathable)
            //
        }, CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS))
                .thenComposeAsync(v -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(false);
                    }
                    Item targetItem = findDroppedItem(brokenBlockLocation);
                    if (targetItem == null || targetItem.isDead()) {
                        logger.warning("Could not find dropped item. Assuming it was collected or despawned. Moving on.");
                        return CompletableFuture.completedFuture(false);
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
                        logger.warning("Failed to navigate to pick up item.");
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