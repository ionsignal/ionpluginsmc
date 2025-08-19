package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.CollectItemResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.NavigateToLocationResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
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
public class CollectItemSkill implements Skill<CollectItemResult> {
    private final Item targetItem;

    private record PathFindResult(Optional<Path> path, CollectItemResult reason) {
    }

    public CollectItemSkill(Item targetItem) {
        this.targetItem = targetItem;
    }

    @Override
    public CompletableFuture<CollectItemResult> execute(NerrusAgent agent) {
        if (targetItem == null || targetItem.isDead()) {
            return CompletableFuture.completedFuture(CollectItemResult.ITEM_GONE);
        }
        double distanceSquared = agent.getPersona().getLocation().distanceSquared(targetItem.getLocation());
        if (distanceSquared < 2.25) {
            return attemptPickup();
        }
        return findBestPathToItem(agent)
                .thenCompose(pathFindResult -> {
                    // If pathfinding failed, we have our final result. Complete the future with the reason.
                    if (pathFindResult.path().isEmpty()) {
                        return CompletableFuture.completedFuture(pathFindResult.reason());
                    }
                    // Pathfinding succeeded. Now, execute the navigation skill.
                    // The rest of the logic is now chained *inside* this block.
                    NavigateToLocationSkill navSkill = new NavigateToLocationSkill(pathFindResult.path().get(), targetItem.getLocation());
                    return navSkill.execute(agent)
                            .thenCompose(navResult -> {
                                // This skill is self-contained, so it cleans up its own look target.
                                if (agent.getPersona().isSpawned() && agent.getPersona().getPersonaEntity() != null) {
                                    agent.getPersona().getPersonaEntity().getLookControl().stopLooking();
                                }
                                // If navigation failed, we have our final result.
                                if (navResult != NavigateToLocationResult.SUCCESS) {
                                    CollectItemResult finalResult = navResult == NavigateToLocationResult.CANCELLED
                                            ? CollectItemResult.CANCELLED
                                            : CollectItemResult.NAVIGATION_FAILED;
                                    return CompletableFuture.completedFuture(finalResult);
                                }
                                // Navigation succeeded. Proceed to the pickup attempt.
                                // This ensures the final return type is consistently CompletableFuture<CollectItemResult>.
                                CompletableFuture<CollectItemResult> pickupFuture = new CompletableFuture<>();
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        pickupFuture.complete(
                                                targetItem.isDead() ? CollectItemResult.SUCCESS : CollectItemResult.PICKUP_TIMEOUT);
                                    }
                                }.runTaskLater(IonNerrus.getInstance(), 5L); // 5 ticks (1/4 second) delay
                                return pickupFuture;
                            });
                });
    }

    private CompletableFuture<CollectItemResult> attemptPickup() {
        CompletableFuture<CollectItemResult> pickupFuture = new CompletableFuture<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                // Success is defined as the item no longer being in the world.
                IonNerrus.getInstance().getLogger().warning("attempting pickup distance was too close, result was " + targetItem.isDead());
                pickupFuture.complete(targetItem.isDead() ? CollectItemResult.SUCCESS : CollectItemResult.PICKUP_TIMEOUT);
            }
        }.runTaskLater(IonNerrus.getInstance(), 5L);
        return pickupFuture;
    }

    private CompletableFuture<PathFindResult> findBestPathToItem(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            // This prevents the search from happening in mid-air if the item is falling.
            Optional<Location> probableLandingZone = NavigationHelper.findGround(targetItem.getLocation(), 10);
            if (probableLandingZone.isEmpty()) {
                return new PathFindResult(Optional.empty(), CollectItemResult.NO_CANDIDATE_SPOTS);
            }
            // Generate candidate standing spots around the item.
            List<Location> candidates = generateCandidateSpots(targetItem.getLocation());
            if (candidates.isEmpty()) {
                return new PathFindResult(Optional.empty(), CollectItemResult.NO_CANDIDATE_SPOTS);
            }
            // Evaluate all candidates by pathfinding in parallel.
            List<CompletableFuture<Optional<Path>>> pathFutures = candidates.stream()
                    .map(spot -> AStarPathfinder.findPath(agent.getPersona().getLocation(), spot, NavigationParameters.DEFAULT))
                    .collect(Collectors.toList());
            CompletableFuture.allOf(pathFutures.toArray(new CompletableFuture[0])).join();
            // Select the valid path that ends closest to the item.
            // This prevents selecting a short but useless path if the agent is already standing on a candidate spot far from the item.
            Optional<Path> bestPath = pathFutures.stream()
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
            if (bestPath.isPresent()) {
                return new PathFindResult(bestPath, null);
            } else {
                return new PathFindResult(Optional.empty(), CollectItemResult.NO_PATH_FOUND);
            }
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