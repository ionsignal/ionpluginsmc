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
import com.ionsignal.minecraft.ionnerrus.persona.navigation.WorldSnapshot;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.EngageResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;
import com.ionsignal.minecraft.ionnerrus.util.search.BlockSearch;
import com.ionsignal.minecraft.ionnerrus.util.search.strategy.StandardMovement;

import net.kyori.adventure.text.format.NamedTextColor;

import net.minecraft.core.BlockPos;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A skill for an agent to collect a specific item entity using a two-phase strategy:
 * 
 * 1. Approach: Pathfind and navigate to a valid standing spot near the item.
 * 2. Engage: Use a dynamic, tick-based movement to close the final distance and acquire the item.
 */
public class CollectItemSkill implements Skill<CollectItemResult> {
    public static final boolean VISUALIZE_COLLECT = true;
    private static final double WARN_THRESHOLD_MS = 250.0;
    private static final double APPROACH_DISTANCE_SQUARED = 2.5 * 2.5;
    private static final int COLLECTION_TIMEOUT_SECONDS = 8;
    private static final int MAX_PATHFINDING_ATTEMPTS = 3;
    private static final int MAX_CANDIDATES_TO_FIND = 5;
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    private final Item targetItem;

    public CollectItemSkill(Item targetItem) {
        this.targetItem = targetItem;
    }

    @Override
    public CompletableFuture<CollectItemResult> execute(NerrusAgent agent) {
        if (targetItem == null || targetItem.isDead()) {
            return CompletableFuture.completedFuture(CollectItemResult.ITEM_GONE);
        }
        final long startTime = System.nanoTime();
        final Location agentLocation = agent.getPersona().getLocation();
        final World world = agentLocation.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(CollectItemResult.NAVIGATION_FAILED);
        }
        // Find a stable ground location for the item first.
        Optional<Location> probableLandingZone = findItemLandingBlock(targetItem.getLocation(), 10);
        if (probableLandingZone.isEmpty()) {
            return CompletableFuture.completedFuture(CollectItemResult.NO_STANDPOINTS_FOUND);
        }
        final Location itemGroundLocation = probableLandingZone.get();
        Navigator navigator = agent.getPersona().getNavigator();
        CompletableFuture<CollectItemResult> finalResultFuture = new CompletableFuture<>();
        // Set up a timeout for the entire operation.
        finalResultFuture.orTimeout(COLLECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((result, ex) -> {
            if (ex != null) {
                navigator.cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
                finalResultFuture.complete(CollectItemResult.PICKUP_TIMEOUT);
            }
        });
        // Step 1: Create a single WorldSnapshot for the entire operation.
        double searchRadius = agentLocation.distance(itemGroundLocation) + 16;
        int snapshotPadding = 16;
        BlockPos min = new BlockPos((int) (agentLocation.getX() - searchRadius - snapshotPadding),
                (int) (agentLocation.getY() - searchRadius - snapshotPadding),
                (int) (agentLocation.getZ() - searchRadius - snapshotPadding));
        BlockPos max = new BlockPos((int) (agentLocation.getX() + searchRadius + snapshotPadding),
                (int) (agentLocation.getY() + searchRadius + snapshotPadding),
                (int) (agentLocation.getZ() + searchRadius + snapshotPadding));
        WorldSnapshot.create(world, min, max)
                .thenComposeAsync(snapshot -> findOptimalApproachPath(agent, itemGroundLocation, snapshot),
                        IonNerrus.getInstance().getOffloadThreadExecutor())
                .thenComposeAsync(pathOptional -> {
                    if (pathOptional.isEmpty()) {
                        log(startTime, "NO_PATH_FOUND");
                        finalResultFuture.complete(CollectItemResult.NO_PATH_FOUND);
                        return CompletableFuture.completedFuture(null);
                    }
                    log(startTime, "OPTIMAL_APPROACH_PATH_FOUND");
                    return navigator.navigateTo(pathOptional.get());
                }, IonNerrus.getInstance().getMainThreadExecutor())
                .thenAcceptAsync(navResult -> {
                    if (finalResultFuture.isDone()) {
                        return;
                    }
                    if (navResult != NavigationResult.SUCCESS) {
                        finalResultFuture.complete(CollectItemResult.NAVIGATION_FAILED);
                    } else {
                        engageAndMonitor(agent, navigator, finalResultFuture);
                    }
                }, IonNerrus.getInstance().getMainThreadExecutor());
        return finalResultFuture;
    }

    /**
     * Commands the navigator to engage and starts a monitoring task to manage the final completion of
     * the skill's result future, resolving the race condition between the navigator's state and the
     * inventory check.
     */
    private void engageAndMonitor(NerrusAgent agent, Navigator navigator,
            CompletableFuture<CollectItemResult> finalResultFuture) {
        final int initialCount = countItems(agent, targetItem.getItemStack().getType());
        BukkitTask monitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (finalResultFuture.isDone()) {
                    this.cancel();
                    return;
                }
                if (countItems(agent, targetItem.getItemStack().getType()) > initialCount) {
                    finalResultFuture.complete(CollectItemResult.SUCCESS);
                    navigator.finishEngaging(EngageResult.SUCCESS);
                    this.cancel();
                }
            }
        }.runTaskTimer(IonNerrus.getInstance(), 0L, 5L);
        finalResultFuture.whenComplete((res, err) -> monitorTask.cancel());
        CompletableFuture<EngageResult> engageFuture = navigator.engageOn(targetItem); // TODO: we need to fix this, needs to engageOn
                                                                                       // location object, not an item object
        if (VISUALIZE_COLLECT) {
            IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                DebugVisualizer.highlightBlock(targetItem.getLocation(), 60, NamedTextColor.DARK_AQUA);
            });
        }
        engageFuture.whenComplete((engageResult, throwable) -> {
            if (finalResultFuture.isDone()) {
                return;
            }
            CollectItemResult resultToReport = switch (engageResult) {
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

    /**
     * Finds the first solid surface directly below a starting location, simulating where a falling item
     * would land. This is used to get a stable reference point for the collection search.
     *
     * @param startLocation
     *            The location to start searching from (typically the item's current location).
     * @param searchRange
     *            The maximum number of blocks to search downwards.
     * @return An Optional containing the Location of the passable block directly above the found
     *         surface,
     *         or an empty Optional if no surface is found within range.
     */
    private Optional<Location> findItemLandingBlock(Location startLocation, int searchRange) {
        Block currentBlock = startLocation.getBlock();
        // Search downwards from the item's current position.
        for (int i = 0; i < searchRange; i++) {
            // A landing zone is the first non-passable block we encounter.
            // This correctly handles solid blocks, leaves, slabs, etc.
            if (!NavigationHelper.isPassable(currentBlock)) {
                // The reference point for our search should be the passable space *above* the solid surface.
                return Optional.of(currentBlock.getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5));
            }
            currentBlock = currentBlock.getRelative(BlockFace.DOWN);
        }
        // If we search the whole range and find nothing but air, there's no landing zone.
        return Optional.empty();
    }

    /**
     * Counts the total quantity of a specific material within the agent's inventory.
     * This method iterates through all inventory slots, summing the amount from every
     * stack that matches the target material. It serves as the definitive check for
     * confirming a successful item pickup in the monitoring phase of the skill.
     *
     * @param agent
     *            The NerrusAgent whose inventory will be scanned.
     * @param material
     *            The Material type to count.
     * @return The total number of items of the specified material found in the inventory,
     *         or 0 if the inventory is inaccessible or no items are found.
     */
    private int countItems(NerrusAgent agent, Material material) {
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
     * Finds the optimal path to a valid standing spot near the item using an efficient
     * search-and-evaluate strategy.
     */
    private CompletableFuture<Optional<Path>> findOptimalApproachPath(NerrusAgent agent, Location itemGroundLocation,
            WorldSnapshot snapshot) {
        return CompletableFuture.supplyAsync(() -> {
            final Location agentLocation = agent.getPersona().getLocation();
            final double searchRadius = agentLocation.distance(itemGroundLocation) + 10.0;
            // Step 1: Use BlockSearch to find all reachable locations that are also valid approach points.
            ApproachPointProcessor processor = new ApproachPointProcessor(itemGroundLocation);
            List<Location> candidates = BlockSearch.findReachable(
                    agentLocation, searchRadius, MAX_CANDIDATES_TO_FIND,
                    new StandardMovement(), processor, snapshot);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            // Step 2: Limit pathfinding attempts for performance.
            List<Location> finalCandidates = candidates.stream()
                    .sorted(Comparator.comparingDouble(spot -> agentLocation.distanceSquared(spot)))
                    .limit(MAX_PATHFINDING_ATTEMPTS)
                    .collect(Collectors.toList());
            // Step 3: Pathfind to each candidate and collect all valid paths.
            List<Path> validPaths = new ArrayList<>();
            for (Location candidate : finalCandidates) {
                // Reuse the existing snapshot for every pathfinding call.
                Optional<Path> pathOpt = AStarPathfinder
                        .findPath(agentLocation, candidate, NavigationParameters.DEFAULT, snapshot)
                        .join();
                pathOpt.ifPresent(validPaths::add);
            }
            // Step 4: Return the path with the shortest length.
            return validPaths.stream().min(Comparator.comparingDouble(Path::getLength));
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    private void log(long startTime, String status) {
        long endTime = System.nanoTime();
        double durationMillis = (endTime - startTime) / 1_000_000.0;
        String logMessage = String.format(
                "CollectItemSkill finished `findOptimalApproachPath` in %.3f ms with result: %s",
                durationMillis,
                status);
        if (durationMillis >= WARN_THRESHOLD_MS || status != "OPTIMAL_APPROACH_PATH_FOUND") {
            LOGGER.warning(logMessage);
        } else {
            LOGGER.info(logMessage);
        }
    }

    /**
     * An ISearchProcessor that identifies valid standing spots for collecting an item.
     * A spot is valid if it's within the defined approach distance of the item's location.
     */
    private static class ApproachPointProcessor implements BlockSearch.ISearchProcessor<Location> {
        private final Location itemGroundLocation;

        public ApproachPointProcessor(Location itemGroundLocation) {
            this.itemGroundLocation = itemGroundLocation;
        }

        @Override
        public List<Location> process(BlockSearch.TraversalNode node, World world, WorldSnapshot snapshot) {
            Location standingSpot = new Location(world, node.pos().getX() + 0.5, node.pos().getY(), node.pos().getZ() + 0.5);
            if (standingSpot.distanceSquared(itemGroundLocation) <= APPROACH_DISTANCE_SQUARED) {
                if (VISUALIZE_COLLECT) {
                    IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                        DebugVisualizer.highlightBlock(standingSpot, 20, NamedTextColor.GOLD);
                    });
                }
                return List.of(standingSpot);
            }
            return List.of();
        }
    }
}