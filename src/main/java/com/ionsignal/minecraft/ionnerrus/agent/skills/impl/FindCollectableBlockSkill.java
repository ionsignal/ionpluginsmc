package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.CollectableBlock;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.FindCollectableBlockResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.WorldSnapshot;

import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;
import com.ionsignal.minecraft.ionnerrus.util.search.BlockSearch;
import com.ionsignal.minecraft.ionnerrus.util.search.ScanOffsets;
import com.ionsignal.minecraft.ionnerrus.util.search.strategy.StandardMovement;

import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class FindCollectableBlockSkill implements Skill<FindCollectableBlockResult> {
    public static final boolean VISUALIZE_SEARCH = true;
    private static final double WARN_THRESHOLD_MS = 250.0;
    private static final int MAX_CANDIDATES_TO_FIND = 15;
    private static final int MAX_PATHFINDING_ATTEMPTS = 5;
    private static final double PATH_LENGTH_WEIGHT = 3.0;
    private static final double EXPOSURE_SCORE_WEIGHT = 1.0;
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    private final Set<Material> materials;
    private final int searchRadius;
    private final Set<Location> excludedLocations;

    public FindCollectableBlockSkill(Set<Material> materials, int searchRadius, Set<Location> excludedLocations) {
        this.materials = materials;
        this.searchRadius = searchRadius;
        this.excludedLocations = excludedLocations;
    }

    @Override
    public CompletableFuture<FindCollectableBlockResult> execute(NerrusAgent agent) {
        Location start = agent.getPersona().getLocation();
        World world = start.getWorld();
        if (world == null) {
            return CompletableFuture
                    .completedFuture(FindCollectableBlockResult.failure(FindCollectableBlockResult.Status.NO_TARGETS_FOUND, Set.of()));
        }
        int snapshotPadding = 16;
        BlockPos min = new BlockPos(start.getBlockX() - searchRadius - snapshotPadding, start.getBlockY() - searchRadius - snapshotPadding,
                start.getBlockZ() - searchRadius - snapshotPadding);
        BlockPos max = new BlockPos(start.getBlockX() + searchRadius + snapshotPadding, start.getBlockY() + searchRadius + snapshotPadding,
                start.getBlockZ() + searchRadius + snapshotPadding);
        if (VISUALIZE_SEARCH) {
            Location corner1 = start.clone().add(searchRadius, searchRadius, searchRadius);
            Location corner2 = start.clone().subtract(searchRadius, searchRadius, searchRadius);
            DebugVisualizer.visualizeBoundingBox(corner1, corner2, 20, NamedTextColor.AQUA);
        }
        return WorldSnapshot.create(world, min, max)
                .thenApplyAsync(snapshot -> {
                    long startTime = System.nanoTime();
                    // Initialize a master cache for this entire search operation preventing re-processing the same
                    // target block from different standing spots.
                    StandardMovement movementStrategy = new StandardMovement();
                    // Pass the master cache to the processor's constructor.
                    CollectableTargetProcessor searchProcessor = new CollectableTargetProcessor();
                    List<CollectionCandidate> candidates = BlockSearch.findReachable(
                            start, searchRadius, MAX_CANDIDATES_TO_FIND,
                            movementStrategy, searchProcessor, snapshot);
                    Set<Material> allFoundMaterials = candidates.stream()
                            .map(candidate -> snapshot.getBlockState(
                                    new BlockPos(
                                            candidate.targetBlockLocation().getBlockX(),
                                            candidate.targetBlockLocation().getBlockY(),
                                            candidate.targetBlockLocation().getBlockZ()))
                                    .getBukkitMaterial())
                            .collect(Collectors.toSet());
                    if (candidates.isEmpty()) {
                        log(startTime, FindCollectableBlockResult.Status.NO_TARGETS_FOUND);
                        return FindCollectableBlockResult.failure(FindCollectableBlockResult.Status.NO_TARGETS_FOUND, allFoundMaterials);
                    }
                    Optional<CollectableBlock> finalTarget = evaluateCandidates(start, candidates, snapshot);
                    // Construct the final result using the new factory methods, passing the found materials.
                    FindCollectableBlockResult finalResult = finalTarget
                            .map(target -> FindCollectableBlockResult.success(target, allFoundMaterials))
                            .orElse(FindCollectableBlockResult.failure(FindCollectableBlockResult.Status.NO_PATH_FOUND, allFoundMaterials));
                    log(startTime, finalResult.status());
                    return finalResult;
                }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    // CHANGE: New private record for scoring
    private record ScoredCandidate(CollectableBlock target, double score) implements Comparable<ScoredCandidate> {
        @Override
        public int compareTo(ScoredCandidate other) {
            // Lower score is better
            return Double.compare(this.score, other.score);
        }
    }

    /**
     * CHANGE: This method is completely rewritten to implement a multi-factor scoring system.
     * It now considers path length and block exposure to find the truly optimal target.
     */
    private Optional<CollectableBlock> evaluateCandidates(Location agentLocation, List<CollectionCandidate> candidates,
            WorldSnapshot snapshot) {
        // Step 1: De-duplicate targets, keeping only the best standing spot for each.
        // This ensures we don't pathfind to the same block multiple times from different spots.
        Map<Location, CollectionCandidate> bestCandidates = new HashMap<>();
        for (CollectionCandidate candidate : candidates) {
            bestCandidates.compute(candidate.targetBlockLocation(), (key, existing) -> {
                if (existing == null ||
                        agentLocation.distanceSquared(candidate.standingSpot()) < agentLocation.distanceSquared(existing.standingSpot())) {
                    return candidate;
                }
                return existing;
            });
        }
        // Step 2: Limit the number of pathfinding operations for performance.
        List<CollectionCandidate> finalCandidates = bestCandidates.values().stream()
                .sorted(Comparator.comparingDouble(c -> agentLocation.distanceSquared(c.standingSpot())))
                .limit(MAX_PATHFINDING_ATTEMPTS)
                .collect(Collectors.toList());
        // Step 3: Pathfind and score each candidate.
        List<ScoredCandidate> scoredAndPathable = new ArrayList<>();
        for (CollectionCandidate candidate : finalCandidates) {
            if (VISUALIZE_SEARCH) {
                IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                    DebugVisualizer.highlightBlock(candidate.standingSpot(), 60, NamedTextColor.LIGHT_PURPLE);
                });
            }
            Optional<Path> pathOpt = AStarPathfinder
                    .findPath(agentLocation, candidate.standingSpot(), NavigationParameters.DEFAULT, snapshot)
                    .join();
            if (pathOpt.isPresent()) {
                Path path = pathOpt.get();
                // Calculate the final score for this valid, pathable candidate.
                double exposureScore = calculateExposureScore(candidate.targetBlockLocation(), snapshot);
                double pathLength = path.getLength();
                double score = (pathLength * PATH_LENGTH_WEIGHT) - (exposureScore * EXPOSURE_SCORE_WEIGHT);
                scoredAndPathable.add(new ScoredCandidate(
                        new CollectableBlock(candidate.targetBlockLocation(), candidate.standingSpot(), path),
                        score));
            }
        }
        // Step 4: Return the candidate with the best (lowest) final score.
        return scoredAndPathable.stream()
                .min(Comparator.naturalOrder())
                .map(ScoredCandidate::target);
    }

    /**
     * Calculate a block's exposure score. A higher score means more faces are exposed to air, making it
     * easier to break.
     */
    private int calculateExposureScore(Location blockLocation, WorldSnapshot snapshot) {
        int score = 0;
        BlockPos centerPos = new BlockPos(blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0)
                        continue;
                    BlockPos adjacentPos = centerPos.offset(dx, dy, dz);
                    BlockState adjacentState = snapshot.getBlockState(adjacentPos);
                    if (adjacentState == null || adjacentState.getCollisionShape(EmptyBlockGetter.INSTANCE, adjacentPos).isEmpty()) {
                        score++;
                    }
                }
            }
        }
        return score;
    }

    private record CollectionCandidate(Location targetBlockLocation, Location standingSpot) {
    }

    /**
     * Use a pre-computed spherical scan to find all potential targets within reach of a standing spot,
     * instead of just checking adjacent blocks. A master cache is used to ensure each potential target
     * is evaluated only once per skill execution for maximum performance.
     */
    private class CollectableTargetProcessor implements BlockSearch.ISearchProcessor<CollectionCandidate> {
        // Added a final field to hold the master cache for the entire skill execution.
        // private final Set<BlockPos> processedTargetBlocks;

        /**
         * Constructor to accept the master cache.
         * 
         * @param processedTargetBlocks
         *            A set to track all block positions that have been evaluated as
         *            potential targets during this skill's execution.
         */
        public CollectableTargetProcessor() { // (Set<BlockPos> processedTargetBlocks) {
            // this.processedTargetBlocks = processedTargetBlocks;
        }

        /**
         * Iterate through pre-computed spherical offsets to find targets within reach.
         */
        @Override
        public List<CollectionCandidate> process(BlockSearch.TraversalNode node, World world, WorldSnapshot snapshot) {
            List<CollectionCandidate> found = new ArrayList<>();
            BlockPos standingPos = node.pos();
            Location standingLocation = new Location(world, standingPos.getX() + 0.5, standingPos.getY(), standingPos.getZ() + 0.5);
            // The Main Scan Loop: Iterate over pre-computed half-sphere offsets.
            for (BlockPos offset : ScanOffsets.HALF_SPHERE_REACH_OFFSETS) {
                // Step A: Calculate Absolute Position of the potential target.
                BlockPos potentialTargetPos = standingPos.offset(offset);
                // Step B (Optimization 1 - Global Cache): Check if this block has already been processed
                // by any previous call to `process` within this skill's execution. This is the most
                // important optimization, preventing redundant checks for the same block from different standing
                // spots.
                // if (processedTargetBlocks.contains(potentialTargetPos)) {
                // continue; // Already processed from another standing spot, skip.
                // }
                // processedTargetBlocks.add(potentialTargetPos);
                // Step C: World Data Validation: Check if the block is of a desired material.
                BlockState targetState = snapshot.getBlockState(potentialTargetPos);
                if (targetState == null || !materials.contains(targetState.getBukkitMaterial())) {
                    continue;
                }
                // Step D: Gameplay Logic Validation: Check against the skill's exclusion list.
                Location targetBlockLocation = new Location(world, potentialTargetPos.getX(), potentialTargetPos.getY(),
                        potentialTargetPos.getZ());
                if (excludedLocations.contains(targetBlockLocation)) {
                    continue;
                }
                // Step E (Final Reach Check): This is a sanity check to confirm the block is within the
                // agent's physical reach from this specific standing spot, using a precise distance check.
                if (standingLocation.distanceSquared(targetBlockLocation.clone().add(0.5, 0.5, 0.5)) > ScanOffsets.REACH_RADIUS_SQUARED) {
                    continue;
                }
                // Step F (Success): All checks passed. This is a valid candidate.
                found.add(new CollectionCandidate(targetBlockLocation, standingLocation));
                if (VISUALIZE_SEARCH) {
                    IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                        DebugVisualizer.highlightBlock(targetBlockLocation, 20, NamedTextColor.GOLD);
                    });
                }
            }
            return found;
        }
    }

    private void log(long startTime, FindCollectableBlockResult.Status status) {
        long endTime = System.nanoTime();
        double durationMillis = (endTime - startTime) / 1_000_000.0;
        String logMessage = String.format(
                "FindCollectableTargetSkill finished in %.3f ms with result: %s",
                durationMillis,
                status);
        if (durationMillis >= WARN_THRESHOLD_MS || status != FindCollectableBlockResult.Status.SUCCESS) {
            LOGGER.warning(logMessage);
        } else {
            LOGGER.info(logMessage);
        }
    }
}