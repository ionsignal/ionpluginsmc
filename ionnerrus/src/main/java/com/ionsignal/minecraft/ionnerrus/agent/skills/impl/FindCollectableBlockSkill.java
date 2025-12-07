package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

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
    private static final int MAX_CANDIDATES_TO_FIND = 10;
    private static final int MAX_PATHFINDING_ATTEMPTS = 3;

    // Scoring Weights
    private static final double PATH_LENGTH_WEIGHT = 3.0;
    private static final double SCORE_WEIGHT = 1.0;

    // Heuristic Constants
    private static final double OCCLUSION_PENALTY = 30.0; // Cost equivalent to walking ~15 blocks
    private static final double EXPOSURE_BONUS = 2.0; // Bonus per exposed face

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
    public CompletableFuture<FindCollectableBlockResult> execute(NerrusAgent agent, ExecutionToken token) {
        Location start = agent.getPersona().getLocation();
        World world = start.getWorld();
        double eyeHeight = agent.getPersona().getPersonaEntity().getEyeHeight();
        double reach = agent.getPersona().getPhysicalBody().state().getBlockReach();
        double safeReach = reach - 0.5;
        int scanRadius = (int) Math.ceil(safeReach);
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
            DebugVisualizer.visualizeBoundingBox(corner1, corner2, 30, NamedTextColor.AQUA);
        }
        return WorldSnapshot.create(world, min, max)
                .thenApplyAsync(snapshot -> {
                    long startTime = System.nanoTime();
                    // Initialize a master cache for this entire search operation preventing re-processing the same
                    // target block from different standing spots and pass the calculated scanRadius to the processor
                    StandardMovement movementStrategy = new StandardMovement();
                    CollectableTargetProcessor searchProcessor = new CollectableTargetProcessor(reach, eyeHeight, scanRadius);
                    List<CollectionCandidate> candidates = BlockSearch.findReachable(
                            start,
                            searchRadius,
                            MAX_CANDIDATES_TO_FIND,
                            movementStrategy,
                            searchProcessor,
                            snapshot);
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
                    Optional<CollectableBlock> finalTarget = evaluateCandidates(start, candidates, snapshot, token);
                    // Construct the final result using the new factory methods, passing the found materials.
                    FindCollectableBlockResult finalResult = finalTarget
                            .map(target -> FindCollectableBlockResult.success(target, allFoundMaterials))
                            .orElse(FindCollectableBlockResult.failure(FindCollectableBlockResult.Status.NO_PATH_FOUND, allFoundMaterials));
                    log(startTime, finalResult.status());
                    return finalResult;
                }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    // New private record for scoring candidates
    private record ScoredCandidate(CollectableBlock target, double score) implements Comparable<ScoredCandidate> {
        @Override
        public int compareTo(ScoredCandidate other) {
            // Lower score is better
            return Double.compare(this.score, other.score);
        }
    }

    /**
     * This method is completely rewritten to implement a multi-factor scoring system.
     * It now considers path length and block exposure to find the truly optimal target.
     */
    private Optional<CollectableBlock> evaluateCandidates(Location agentLocation, List<CollectionCandidate> candidates,
            WorldSnapshot snapshot, ExecutionToken token) {
        // De-duplication prioritizes heuristic score
        Map<Location, CollectionCandidate> bestCandidates = new HashMap<>();
        for (CollectionCandidate candidate : candidates) {
            bestCandidates.compute(candidate.targetBlockLocation(), (key, existing) -> {
                if (existing == null) {
                    return candidate;
                }
                // Prefer the candidate with the lower heuristic score (better visibility/exposure)
                // If scores are roughly equal, prefer the closer one.
                if (candidate.heuristicScore() < existing.heuristicScore()) {
                    return candidate;
                }
                return existing;
            });
        }
        // Limit the number of pathfinding operations for performance and sort by heuristicScore before
        // limiting to ensure we pathfind to the "best" targets first.
        List<CollectionCandidate> finalCandidates = bestCandidates.values().stream()
                .sorted(Comparator.comparingDouble(CollectionCandidate::heuristicScore))
                .limit(MAX_PATHFINDING_ATTEMPTS)
                .collect(Collectors.toList());
        // Pathfind and score each candidate.
        List<ScoredCandidate> scoredAndPathable = new ArrayList<>();
        for (CollectionCandidate candidate : finalCandidates) {
            if (VISUALIZE_SEARCH) {
                IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                    // Color code: Gold = Exposed/Visible, Red = Occluded
                    NamedTextColor color = candidate.isOccluded() ? NamedTextColor.RED : NamedTextColor.GOLD;
                    DebugVisualizer.highlightBlock(candidate.standingSpot().getBlock().getLocation(), 60, color);
                });
            }
            Optional<Path> pathOpt = AStarPathfinder
                    .findPath(agentLocation, candidate.standingSpot(), NavigationParameters.DEFAULT, snapshot, token)
                    .join();
            if (pathOpt.isPresent()) {
                Path path = pathOpt.get();
                // Calculate the final score for this valid, pathable candidate.
                // We combine the pre-calculated heuristic with the actual path length.
                double pathLength = path.getLength();
                double score = (pathLength * PATH_LENGTH_WEIGHT) + (candidate.heuristicScore() * SCORE_WEIGHT);
                scoredAndPathable.add(new ScoredCandidate(
                        new CollectableBlock(candidate.targetBlockLocation(), candidate.standingSpot(), path),
                        score));
            }
        }
        // Return the candidate with the best (lowest) final score.
        return scoredAndPathable.stream()
                .min(Comparator.naturalOrder())
                .map(ScoredCandidate::target);
    }

    // Expanded record to hold heuristic data
    private record CollectionCandidate(
            Location targetBlockLocation,
            Location standingSpot,
            double heuristicScore,
            boolean isOccluded,
            int exposureCount) {
    }

    /**
     * Use a pre-computed spherical scan to find all potential targets within reach of a standing spot
     * and perform ray-tracing and exposure checks during the search phase.
     */
    private class CollectableTargetProcessor implements BlockSearch.ISearchProcessor<CollectionCandidate> {
        private final double reachSquared;
        private final double eyeHeight;
        private final List<BlockPos> offsets;
        private final BlockPos.MutableBlockPos sharedMutablePos = new BlockPos.MutableBlockPos();

        public CollectableTargetProcessor(double reach, double eyeHeight, int scanRadius) {
            this.reachSquared = reach * reach;
            this.eyeHeight = eyeHeight;
            this.offsets = ScanOffsets.getHalfSphere(scanRadius);
        }

        @Override
        @SuppressWarnings("null")
        public List<CollectionCandidate> process(BlockSearch.TraversalNode node, World world, WorldSnapshot snapshot) {
            List<CollectionCandidate> found = new ArrayList<>();
            BlockPos standingPos = node.pos();
            // Calculate Eye Position for precise reach check and Ray-Tracing
            Location eyeLocation = new Location(world,
                    standingPos.getX() + 0.5,
                    standingPos.getY() + eyeHeight,
                    standingPos.getZ() + 0.5);
            for (BlockPos offset : this.offsets) {
                // Step A: Calculate Absolute Position
                BlockPos potentialTargetPos = standingPos.offset(offset);
                Location targetCenter = new Location(world,
                        potentialTargetPos.getX() + 0.5,
                        potentialTargetPos.getY() + 0.5,
                        potentialTargetPos.getZ() + 0.5);
                // Step B: Distance Check (Fastest)
                double distSq = eyeLocation.distanceSquared(targetCenter);
                if (distSq > reachSquared) {
                    continue;
                }
                // Step C: Material Check (Fast)
                BlockState targetState = snapshot.getBlockState(potentialTargetPos);
                if (targetState == null || !materials.contains(targetState.getBukkitMaterial())) {
                    continue;
                }
                // Step D: Exclusion Check
                Location targetBlockLocation = new Location(world, potentialTargetPos.getX(), potentialTargetPos.getY(),
                        potentialTargetPos.getZ());
                if (excludedLocations.contains(targetBlockLocation)) {
                    continue;
                }
                // Step E: Exposure Check (Medium)
                // We calculate how many faces are touching air/passable blocks.
                int exposure = calculateExposure(potentialTargetPos, snapshot);
                // Step F: Ray-Trace Check (Slowest - performed last)
                // We check if we have Line-of-Sight from eyes to block center.
                boolean isOccluded = checkOcclusion(snapshot, eyeLocation.toVector(), targetCenter.toVector(), potentialTargetPos);
                // Step G: Scoring
                // Score = Distance + (Occluded ? Penalty : 0) - (Exposure * Bonus)
                double distance = Math.sqrt(distSq);
                double score = distance
                        + (isOccluded ? OCCLUSION_PENALTY : 0.0)
                        - (exposure * EXPOSURE_BONUS);
                // Step H: Add Candidate
                Location standingLocation = new Location(world, standingPos.getX() + 0.5, standingPos.getY(), standingPos.getZ() + 0.5);
                found.add(new CollectionCandidate(
                        targetBlockLocation,
                        standingLocation,
                        score,
                        isOccluded,
                        exposure));
            }
            return found;
        }

        // Moved logic from outer class to inner processor
        @SuppressWarnings("null")
        private int calculateExposure(BlockPos pos, WorldSnapshot snapshot) {
            int score = 0;
            // Check 6 cardinal directions
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                sharedMutablePos.setWithOffset(pos, dir);
                BlockState adjacent = snapshot.getBlockState(sharedMutablePos);
                if (adjacent == null || adjacent.getCollisionShape(EmptyBlockGetter.INSTANCE, sharedMutablePos).isEmpty()) {
                    score++;
                }
            }
            return score;
        }

        private boolean checkOcclusion(WorldSnapshot snapshot, Vector start, Vector end, BlockPos targetPos) {
            BlockHitResult hit = snapshot.rayTrace(start, end);
            if (hit.getType() == HitResult.Type.MISS) {
                return false; // Clear view
            }
            BlockPos hitPos = hit.getBlockPos();
            // It's occluded if we hit something that ISN'T the target block
            return !hitPos.equals(targetPos);
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