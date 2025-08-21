package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.CollectItemResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Navigator;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.EngageResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
// import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A skill for an agent to collect a specific item entity using a two-phase strategy:
 * 
 * 1. Approach: Pathfind and navigate to a valid standing spot near the item.
 * 2. Engage: Use a dynamic, tick-based movement to close the final distance and acquire the item.
 */
public class CollectItemSkill implements Skill<CollectItemResult> {
    public static final boolean VISUALIZE_SEARCH = true;
    private static final double APPROACH_DISTANCE_SQUARED = 3.0 * 3.0;
    private static final int COLLECTION_TIMEOUT_SECONDS = 8;
    private static final int MAX_CANDIDATES_TO_PATHFIND = 5;
    // private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    private final Item targetItem;

    public CollectItemSkill(Item targetItem) {
        this.targetItem = targetItem;
    }

    @Override
    public CompletableFuture<CollectItemResult> execute(NerrusAgent agent) {
        if (targetItem == null || targetItem.isDead()) {
            return CompletableFuture.completedFuture(CollectItemResult.ITEM_GONE);
        }
        Navigator navigator = agent.getPersona().getNavigator();
        CompletableFuture<CollectItemResult> finalResultFuture = new CompletableFuture<>();
        // Set up a timeout for the entire operation.
        finalResultFuture.orTimeout(COLLECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((result, ex) -> {
            if (ex != null) {
                // If the future was completed by the timeout, ensure we cancel the navigator.
                navigator.cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
                // Explicitly complete with a timeout result if not already done.
                finalResultFuture.complete(CollectItemResult.PICKUP_TIMEOUT);
            }
        });
        // Find the best path to an "approach point" near the item.
        findBestApproachPath(agent)
                .thenComposeAsync(pathOptional -> {
                    if (pathOptional.isEmpty()) {
                        // Pathfinding failed, complete with the reason.
                        finalResultFuture.complete(CollectItemResult.NO_PATH_FOUND);
                        return CompletableFuture.completedFuture(null); // End this chain
                    }
                    // Navigate along the approach path. The result of this navigation
                    // will be handled in the next stage.
                    return navigator.navigateTo(pathOptional.get());
                }, IonNerrus.getInstance().getMainThreadExecutor())
                .thenAcceptAsync(navResult -> {
                    // This block is now the final stage of the main chain.
                    if (finalResultFuture.isDone()) {
                        return; // The skill has already been completed (e.g., by pathfinding failure).
                    }
                    if (navResult != NavigationResult.SUCCESS) {
                        finalResultFuture.complete(CollectItemResult.NAVIGATION_FAILED);
                    } else {
                        // Navigation succeeded. Begin the engage and monitor phase.
                        // This method will now manage the finalResultFuture itself.
                        engageAndMonitor(agent, navigator, finalResultFuture);
                    }
                }, IonNerrus.getInstance().getMainThreadExecutor());

        return finalResultFuture;
    }

    /**
     * Commands the navigator to engage and starts a monitoring task.
     * This method manages the final completion of the skill's result future,
     * resolving the race condition between the navigator's state and the inventory check.
     */
    private void engageAndMonitor(NerrusAgent agent, Navigator navigator,
            CompletableFuture<CollectItemResult> finalResultFuture) {
        // The monitor is a Bukkit task that actively checks for pickup success.
        // It is the SOLE source of a SUCCESS result.
        final int initialCount = countItems(agent, targetItem.getItemStack().getType());
        BukkitTask monitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (finalResultFuture.isDone()) {
                    this.cancel();
                    return;
                }
                // Check for success condition: item is in inventory.
                if (countItems(agent, targetItem.getItemStack().getType()) > initialCount) {
                    finalResultFuture.complete(CollectItemResult.SUCCESS);
                    navigator.finishEngaging(EngageResult.SUCCESS); // Tell navigator it can stop.
                    this.cancel();
                }
            }
        }.runTaskTimer(IonNerrus.getInstance(), 0L, 5L); // Check every 5 ticks

        // When the final result is determined (by any means), cancel the monitor.
        finalResultFuture.whenComplete((res, err) -> monitorTask.cancel());

        // Start the navigator's engage operation and listen for its outcome.
        // This is now a source of FAILURE results only.
        CompletableFuture<EngageResult> engageFuture = navigator.engageOn(targetItem);
        engageFuture.whenComplete((engageResult, throwable) -> {
            // CRITICAL: Only complete the future if it hasn't already been completed
            // by the monitor task (success) or the timeout.
            if (finalResultFuture.isDone()) {
                return;
            }
            // Map the navigator's result to the skill's final result.
            CollectItemResult resultToReport = switch (engageResult) {
                // SUCCESS from navigator is ignored; only the inventory monitor can declare success.
                case SUCCESS -> null;
                case TARGET_GONE -> CollectItemResult.ITEM_GONE;
                case STUCK -> CollectItemResult.NAVIGATION_FAILED;
                case TIMED_OUT -> CollectItemResult.PICKUP_TIMEOUT;
                case CANCELLED -> CollectItemResult.CANCELLED;
            };
            if (resultToReport != null) {
                finalResultFuture.complete(resultToReport);
            }
        });
    }

    private int countItems(NerrusAgent agent, org.bukkit.Material material) {
        PlayerInventory inventory = agent.getPersona().getInventory();
        if (inventory == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * Finds the best path to a valid standing spot within approach distance of the target item.
     */
    private CompletableFuture<Optional<Path>> findBestApproachPath(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            final Location agentLocation = agent.getPersona().getLocation();
            // Find a stable ground location for the item first.
            Optional<Location> probableLandingZone = NavigationHelper.findGround(targetItem.getLocation(), 10);
            if (probableLandingZone.isEmpty()) {
                return Optional.empty();
            }
            // Generate candidate standing spots around the item.
            List<Location> candidates = generateCandidateSpots(probableLandingZone.get());
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            // Sort candidates by straight-line distance as a cheap heuristic
            // to prioritize closer targets, then limit the number of pathfinding operations.
            List<CompletableFuture<Optional<Path>>> pathFutures = candidates.stream()
                    .sorted(Comparator.comparingDouble(spot -> agentLocation.distanceSquared(spot)))
                    .limit(MAX_CANDIDATES_TO_PATHFIND)
                    .map(spot -> AStarPathfinder.findPath(agentLocation, spot, NavigationParameters.DEFAULT))
                    .collect(Collectors.toList());
            if (pathFutures.isEmpty()) {
                return Optional.empty();
            }
            CompletableFuture.allOf(pathFutures.toArray(new CompletableFuture[0])).join();
            return pathFutures.stream()
                    .map(future -> future.getNow(Optional.empty()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .min(Comparator.comparingDouble(Path::getLength));
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    /**
     * Generates a list of valid standing locations around a target location.
     */
    private List<Location> generateCandidateSpots(Location center) {
        List<Location> spots = new ArrayList<>();
        World world = center.getWorld();
        if (world == null)
            return spots;
        int searchRadius = (int) Math.ceil(Math.sqrt(APPROACH_DISTANCE_SQUARED));
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        for (int x = centerX - searchRadius; x <= centerX + searchRadius; x++) {
            for (int z = centerZ - searchRadius; z <= centerZ + searchRadius; z++) {
                // Check a small vertical range around the item's Y level.
                for (int y = centerY - 2; y <= centerY + 2; y++) {
                    Location candidateLoc = new Location(world, x, y, z);
                    if (candidateLoc.distanceSquared(center) > APPROACH_DISTANCE_SQUARED) {
                        continue;
                    }
                    if (NavigationHelper.isValidStandingSpot(candidateLoc.getBlock())) {
                        spots.add(candidateLoc.add(0.5, 0, 0.5));
                    }
                }
            }
        }
        return spots;
    }
}