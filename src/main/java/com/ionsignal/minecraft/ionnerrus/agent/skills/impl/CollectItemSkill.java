package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * A skill for an agent to navigate to and collect a specific item entity.
 */
public class CollectItemSkill implements Skill<Boolean> {
    private final Item targetItem;

    public CollectItemSkill(Item targetItem) {
        this.targetItem = targetItem;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        if (targetItem == null || targetItem.isDead()) {
            return CompletableFuture.completedFuture(true);
        }
        double distanceSquared = agent.getPersona().getLocation().distanceSquared(targetItem.getLocation());
        if (distanceSquared < 2.25) {
            return attemptPickup();
        }
        return findBestPathToItem(agent)
                .thenCompose(pathToItemOpt -> {
                    if (pathToItemOpt.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    // Use the new skill to navigate while looking at the item.
                    NavigateToLocationSkill navSkill = new NavigateToLocationSkill(pathToItemOpt.get(), targetItem.getLocation());
                    return navSkill.execute(agent);
                })
                .thenCompose(navSuccess -> {
                    // This skill is self-contained, so it cleans up its own look target.
                    if (agent.getPersona().isSpawned() && agent.getPersona().getPersonaEntity() != null) {
                        agent.getPersona().getPersonaEntity().getLookControl().stopLooking();
                    }
                    if (!navSuccess) {
                        return CompletableFuture.completedFuture(false);
                    }
                    // Wait a moment to ensure the agent's physics engine picks up the item.
                    CompletableFuture<Boolean> pickupFuture = new CompletableFuture<>();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            // Success is defined as the item no longer being in the world.
                            pickupFuture.complete(targetItem.isDead());
                        }
                    }.runTaskLater(IonNerrus.getInstance(), 5L); // 5 ticks (1/4 second) delay
                    return pickupFuture;
                });
    }

    /**
     * Waits a short fixed duration and then checks if the target item has been picked up.
     * 
     * @return A future that completes with true if the item is gone, false otherwise.
     */
    private CompletableFuture<Boolean> attemptPickup() {
        CompletableFuture<Boolean> pickupFuture = new CompletableFuture<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                // Success is defined as the item no longer being in the world.
                IonNerrus.getInstance().getLogger().warning("attempting pickup distance was too close, result was " + targetItem.isDead());
                pickupFuture.complete(targetItem.isDead());
            }
        }.runTaskLater(IonNerrus.getInstance(), 5L);
        return pickupFuture;
    }

    /**
     * Finds the best path to a reachable spot near the target item.
     */
    private CompletableFuture<Optional<Path>> findBestPathToItem(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            // This prevents the search from happening in mid-air if the item is falling.
            Optional<Location> probableLandingZone = NavigationHelper.findGround(targetItem.getLocation(), 10);
            if (probableLandingZone.isEmpty()) {
                // Could not find a reasonable place where the item might land.
                return Optional.empty();
            }

            // Generate candidate standing spots around the item.
            List<Location> candidates = generateCandidateSpots(targetItem.getLocation());
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            // Evaluate all candidates by pathfinding in parallel.
            List<CompletableFuture<Optional<Path>>> pathFutures = candidates.stream()
                    .map(spot -> AStarPathfinder.findPath(agent.getPersona().getLocation(), spot, NavigationParameters.DEFAULT))
                    .collect(Collectors.toList());
            CompletableFuture.allOf(pathFutures.toArray(new CompletableFuture[0])).join();
            // Select the valid path that ends closest to the item.
            // This prevents selecting a short but useless path if the agent is already standing on a candidate spot far from the item.
            return pathFutures.stream()
                    .map(future -> future.getNow(Optional.empty()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .min(Comparator.comparingDouble(path -> {
                        List<Location> waypoints = path.waypoints();
                        if (waypoints.isEmpty()) {
                            return Double.MAX_VALUE;
                        }
                        return waypoints.get(waypoints.size() - 1).distanceSquared(targetItem.getLocation());
                    }));
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    /**
     * Generates a list of valid standing locations in a 3x3 horizontal area around a target location.
     */
    private List<Location> generateCandidateSpots(Location center) {
        List<Location> spots = new ArrayList<>();
        World world = center.getWorld();
        if (world == null)
            return spots;
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                // The center Y is likely on the ground, so we only need to check a small vertical range.
                for (int y = centerY - 1; y <= centerY + 1; y++) {
                    Block candidateBlock = world.getBlockAt(x, y, z);
                    // Use the new centralized utility to check for valid standing spots.
                    // This ensures consistency with the pathfinder and correctly handles blocks like leaves.
                    if (NavigationHelper.isValidStandingSpot(candidateBlock)) {
                        spots.add(candidateBlock.getLocation().add(0.5, 0, 0.5));
                    }
                }
            }
        }
        return spots;
    }
}