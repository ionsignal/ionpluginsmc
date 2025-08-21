package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.CollectableTarget;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.FindCollectableTargetResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A skill to find the best block of a given material to collect. It uses a three-phase
 * "Exposure-First" strategy:
 * 
 * 1. Find blocks with the highest "exposure" (most open adjacent faces).
 * 2. Find valid standing locations for the most exposed blocks.
 * 3. Pathfind to the best standing locations to select the final target.
 */
public class FindCollectableTargetSkill implements Skill<FindCollectableTargetResult> {
    public static final boolean VISUALIZE_SEARCH = true;
    private static final double WARN_THRESHOLD_MS = 250.0;
    private static final double EXPOSURE_WEIGHT = 40.0;
    private static final int VERTICAL_SEARCH_RADIUS = 8;
    private static final int MAX_PATHFINDING_ATTEMPTS = 10;
    private static final int MAX_EXPOSED_BLOCKS_TO_CONSIDER = 30;
    private static final double REACH_RADIUS_SQUARED = 5.0 * 5.0;

    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST };

    private final Set<Material> materials;
    private final int searchRadius;
    private final Set<Location> excludedLocations;

    public FindCollectableTargetSkill(Set<Material> materials, int searchRadius, Set<Location> excludedLocations) {
        this.materials = materials;
        this.searchRadius = searchRadius;
        this.excludedLocations = excludedLocations;
    }

    @Override
    public CompletableFuture<FindCollectableTargetResult> execute(NerrusAgent agent) {
        // Visualize the entire search area if enabled.
        Location start = agent.getPersona().getLocation();
        if (VISUALIZE_SEARCH) {
            Location corner1 = start.clone().add(searchRadius, VERTICAL_SEARCH_RADIUS, searchRadius);
            Location corner2 = start.clone().subtract(searchRadius, VERTICAL_SEARCH_RADIUS, searchRadius);
            DebugVisualizer.visualizeBoundingBox(corner1, corner2, 10, NamedTextColor.AQUA);
        }
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            FindCollectableTargetResult finalResult;
            // Location start = agent.getPersona().getLocation();
            // Find the most promising block targets based on exposure.
            List<ExposedBlock> exposedBlocks = findExposedBlocks(start);
            if (exposedBlocks.isEmpty()) {
                finalResult = FindCollectableTargetResult.failure(FindCollectableTargetResult.Status.NO_TARGETS_FOUND);
                log(startTime, finalResult);
                return finalResult;
            }
            // Find all valid standing spots for the promising targets.
            List<CollectionCandidate> candidates = findStandpoints(exposedBlocks);
            if (candidates.isEmpty()) {
                finalResult = FindCollectableTargetResult.failure(FindCollectableTargetResult.Status.NO_STANDPOINTS_FOUND);
                log(startTime, finalResult);
                return finalResult;
            }
            // Pathfind from the best candidates to find the optimal target.
            Optional<CollectableTarget> finalTarget = evaluateCandidates(start, candidates);
            finalResult = finalTarget
                    .map(FindCollectableTargetResult::success)
                    .orElse(FindCollectableTargetResult.failure(FindCollectableTargetResult.Status.NO_PATH_FOUND));
            log(startTime, finalResult);
            return finalResult;
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    /**
     * Scans in a spiral to find blocks of the target materials, scoring them by how many adjacent faces
     * are exposed to air.
     */
    private List<ExposedBlock> findExposedBlocks(Location center) {
        List<ExposedBlock> foundBlocks = new ArrayList<>();
        World world = center.getWorld();
        if (world == null)
            return foundBlocks;
        int startX = center.getBlockX();
        int startY = center.getBlockY();
        int startZ = center.getBlockZ();
        for (int r = 0; r <= searchRadius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) < r && Math.abs(z) < r)
                        continue; // Only check the outer ring of the spiral
                    // The vertical scan is now fixed and much smaller
                    for (int y = -VERTICAL_SEARCH_RADIUS; y <= VERTICAL_SEARCH_RADIUS; y++) {
                        Block currentBlock = world.getBlockAt(startX + x, startY + y, startZ + z);
                        if (materials.contains(currentBlock.getType()) && !excludedLocations.contains(currentBlock.getLocation())) {
                            int score = calculateExposureScore(currentBlock);
                            if (score > 0) {
                                foundBlocks.add(new ExposedBlock(currentBlock, score));
                            }
                        }
                    }
                }
            }
        }
        // Instead of sorting only by score, we now sort by a combined score that heavily penalizes
        // distance.
        foundBlocks.sort((a, b) -> {
            double distSqA = center.distanceSquared(a.block().getLocation());
            double distSqB = center.distanceSquared(b.block().getLocation());
            double desirabilityA = (a.score() * EXPOSURE_WEIGHT) - distSqA;
            double desirabilityB = (b.score() * EXPOSURE_WEIGHT) - distSqB;
            // Sort descending by desirability
            return Double.compare(desirabilityB, desirabilityA);
        });
        // Return the top candidates after our new, more intelligent sort.
        if (foundBlocks.size() > MAX_EXPOSED_BLOCKS_TO_CONSIDER) {
            return foundBlocks.subList(0, MAX_EXPOSED_BLOCKS_TO_CONSIDER);
        } else {
            return foundBlocks;
        }
    }

    private int calculateExposureScore(Block block) {
        int score = 0;
        for (BlockFace face : FACES) {
            if (NavigationHelper.isPassable(block.getRelative(face))) {
                score++;
            }
        }
        return score;
    }

    /**
     * For a given list of high-quality target blocks, find all valid standing spots within reach.
     */
    private List<CollectionCandidate> findStandpoints(List<ExposedBlock> targetBlocks) {
        List<CollectionCandidate> candidates = new ArrayList<>();
        for (ExposedBlock exposedBlock : targetBlocks) {
            Block block = exposedBlock.block();
            Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
            int searchRadius = (int) Math.ceil(Math.sqrt(REACH_RADIUS_SQUARED));
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dy = -searchRadius; dy <= searchRadius; dy++) {
                    for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                        Block candidateStandBlock = block.getRelative(dx, dy, dz);
                        if (candidateStandBlock.getLocation().add(0.5, 0, 0.5).distanceSquared(blockCenter) > REACH_RADIUS_SQUARED) {
                            continue;
                        }
                        if (NavigationHelper.isValidStandingSpot(candidateStandBlock)) {
                            candidates.add(new CollectionCandidate(block, candidateStandBlock.getLocation()));
                        }
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Takes the final list of candidates, sorts them by a cheap heuristic, and runs the expensive
     * pathfinder on the best few to find the optimal one.
     */
    private Optional<CollectableTarget> evaluateCandidates(Location agentLocation, List<CollectionCandidate> candidates) {
        // Sort by distance, then limit the number of expensive pathfinding checks
        List<CollectionCandidate> sortedCandidates = candidates.stream()
                .sorted(Comparator.comparingDouble(c -> agentLocation.distanceSquared(c.standingSpot())))
                .limit(MAX_PATHFINDING_ATTEMPTS)
                .collect(Collectors.toList());
        for (CollectionCandidate candidate : sortedCandidates) {
            if (VISUALIZE_SEARCH) {
                IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                    DebugVisualizer.highlightBlock(candidate.standingSpot(), 100, NamedTextColor.LIGHT_PURPLE); // 5 seconds
                });
            }
            Optional<Path> pathOpt = AStarPathfinder.findPath(agentLocation, candidate.standingSpot(), NavigationParameters.DEFAULT)
                    .join();
            if (pathOpt.isPresent()) {
                return Optional.of(new CollectableTarget(
                        candidate.targetBlock().getLocation(),
                        candidate.standingSpot(),
                        pathOpt.get()));
            } else {
                // If pathfinding fails for this candidate, add its target block's location to the exclusion list.
                this.excludedLocations.add(candidate.targetBlock().getLocation());
            }
        }
        return Optional.empty();
    }

    /**
     * A temporary container for a target block and its calculated exposure score.
     */
    private record ExposedBlock(Block block, int score) implements Comparable<ExposedBlock> {
        @Override
        public int compareTo(ExposedBlock other) {
            // Sort in descending order of score.
            return Integer.compare(other.score, this.score);
        }
    }

    /**
     * A temporary container linking a target block to a potential standing spot.
     */
    private record CollectionCandidate(Block targetBlock, Location standingSpot) {
    }

    /**
     * Logs the execution of skill.
     */
    private void log(long startTime, FindCollectableTargetResult result) {
        long endTime = System.nanoTime();
        double durationMillis = (endTime - startTime) / 1_000_000.0;
        String logMessage = String.format(
                "FindCollectableTargetSkill finished in %.3f ms with result: %s",
                durationMillis,
                result.status());
        if (durationMillis >= WARN_THRESHOLD_MS || !result.status().equals(FindCollectableTargetResult.Status.SUCCESS)) {
            LOGGER.warning(logMessage);
        } else {
            LOGGER.info(logMessage);
        }
    }
}