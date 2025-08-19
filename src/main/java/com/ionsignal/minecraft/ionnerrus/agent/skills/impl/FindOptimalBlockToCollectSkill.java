package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.OptimalCollectTarget;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
// import com.ionsignal.minecraft.ionnerrus.util.DebugPath;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

// This class is in flux, lots of changes to
public class FindOptimalBlockToCollectSkill implements Skill<Optional<OptimalCollectTarget>> {
    private static final boolean PARALLEL_PATHFINDING_ENABLED = false; // This is not ready, needs to handle large candidate count (easily
                                                                       // thousands possible)
    private static final double MAX_REACH_SQUARED = 6.0 * 6.0; // 36, a generous 6-block reach.
    private static final int REACH_RADIUS = 6; // The radius to search for standing spots around a target block.
    private static final int MAX_SEQUENTIAL_CANDIDATES_TO_CHECK = 3;

    private final Set<Material> materials;
    private final int searchRadius;
    private final Set<Location> ignoreLocations;

    /**
     * A private record to hold intermediate candidate data before pathfinding.
     */
    private record CollectionCandidate(Location blockLocation, Location standingLocation) {
    }

    public FindOptimalBlockToCollectSkill(Set<Material> materials, int searchRadius, Set<Location> ignoreLocations) {
        this.materials = materials;
        this.searchRadius = searchRadius;
        this.ignoreLocations = ignoreLocations;
    }

    @Override
    public CompletableFuture<Optional<OptimalCollectTarget>> execute(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            // --- Phase 1: Candidate Generation (Fast, Synchronous) ---
            // // DEBUG
            // DebugPath.logAreaAround(agent.getPersona().getLocation(), 6);
            // // DEBUG
            List<CollectionCandidate> candidates = generateCandidates(agent.getPersona().getLocation());
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            // CHANGE: Added a conditional block to switch between parallel and sequential pathfinding.
            if (PARALLEL_PATHFINDING_ENABLED) {
                // --- Phase 2 (Parallel): Fire all pathfinding jobs at once. ---
                Map<CompletableFuture<Optional<Path>>, CollectionCandidate> futureMap = new HashMap<>();
                for (CollectionCandidate candidate : candidates) {
                    CompletableFuture<Optional<Path>> pathFuture = AStarPathfinder.findPath(
                            agent.getPersona().getLocation(),
                            candidate.standingLocation(),
                            NavigationParameters.DEFAULT);
                    futureMap.put(pathFuture, candidate);
                }
                // Wait for all pathfinding operations to complete
                CompletableFuture.allOf(futureMap.keySet().toArray(new CompletableFuture[0])).join();
                // --- Phase 3 (Parallel): Score all results and select the best. ---
                List<OptimalCollectTarget> validTargets = futureMap.entrySet().stream()
                        .map(entry -> {
                            Optional<Path> pathOpt = entry.getKey().getNow(Optional.empty());
                            CollectionCandidate candidate = entry.getValue();
                            return pathOpt.map(path -> new OptimalCollectTarget(
                                    candidate.blockLocation(),
                                    candidate.standingLocation(),
                                    path));
                        })
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
                if (validTargets.isEmpty()) {
                    return Optional.empty();
                }
                // Find the best target based on the scoring function (lower score is better)
                return validTargets.stream().min(Comparator.comparingDouble(this::calculateScore));
            } else {
                // --- Phase 2 & 3 (Optimized Sequential): Find and evaluate a limited set of the best candidates ---
                final Location agentLocation = agent.getPersona().getLocation();
                // Sort candidates by straight-line distance to the standing location (heuristic).
                candidates.sort(Comparator.comparingDouble(c -> agentLocation.distanceSquared(c.standingLocation())));
                // Limit the number of candidates to check to avoid excessive pathfinding.
                List<CollectionCandidate> bestCandidates = candidates.stream()
                        .limit(MAX_SEQUENTIAL_CANDIDATES_TO_CHECK)
                        .collect(Collectors.toList());
                OptimalCollectTarget bestTarget = null;
                for (CollectionCandidate candidate : bestCandidates) {
                    // Execute and wait for a single pathfinding job to complete.
                    Optional<Path> pathOpt = AStarPathfinder.findPath(
                            agentLocation,
                            candidate.standingLocation(),
                            NavigationParameters.DEFAULT).join(); // .join() makes this synchronous
                    if (pathOpt.isPresent()) {
                        OptimalCollectTarget currentTarget = new OptimalCollectTarget(
                                candidate.blockLocation(),
                                candidate.standingLocation(),
                                pathOpt.get());
                        // If this is the first valid target, or if it's better than the previous best, update it.
                        if (bestTarget == null || calculateScore(currentTarget) < calculateScore(bestTarget)) {
                            bestTarget = currentTarget;
                        }
                    }
                }
                return Optional.ofNullable(bestTarget);
            }
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    private List<CollectionCandidate> generateCandidates(Location center) {
        Set<CollectionCandidate> candidateSet = new HashSet<>();
        World world = center.getWorld();
        if (world == null)
            return new ArrayList<>();
        // Spiral search outwards from the 'center' location to find target blocks.
        int x = 0, z = 0;
        int dx = 0, dz = -1;
        int maxI = searchRadius * searchRadius;
        for (int i = 0; i < maxI; i++) {
            if ((-searchRadius / 2 <= x) && (x <= searchRadius / 2) && (-searchRadius / 2 <= z)
                    && (z <= searchRadius / 2)) {
                for (int y = -searchRadius; y <= searchRadius; y++) {
                    Block block = world.getBlockAt(center.getBlockX() + x, center.getBlockY() + y,
                            center.getBlockZ() + z);
                    if (!materials.contains(block.getType()) || ignoreLocations.contains(block.getLocation())) {
                        continue;
                    }
                    // For each target block, perform a volumetric search around it for valid standing spots.
                    Location blockLocation = block.getLocation();
                    Location blockCenter = blockLocation.clone().add(0.5, 0.5, 0.5);
                    for (int sx = -REACH_RADIUS; sx <= REACH_RADIUS; sx++) {
                        for (int sy = -REACH_RADIUS; sy <= REACH_RADIUS; sy++) {
                            for (int sz = -REACH_RADIUS; sz <= REACH_RADIUS; sz++) {
                                Block potentialStandBlock = world.getBlockAt(
                                        blockLocation.getBlockX() + sx,
                                        blockLocation.getBlockY() + sy,
                                        blockLocation.getBlockZ() + sz);
                                if (isValidStandingSpot(potentialStandBlock)) {
                                    Location standLocation = potentialStandBlock.getLocation().add(0.5, 0, 0.5);
                                    if (standLocation.distanceSquared(blockCenter) <= MAX_REACH_SQUARED) {
                                        candidateSet.add(new CollectionCandidate(block.getLocation(), standLocation));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if ((x == z) || ((x < 0) && (x == -z)) || ((x > 0) && (x == 1 - z))) {
                int temp = dx;
                dx = -dz;
                dz = temp;
            }
            x += dx;
            z += dz;
        }
        return new ArrayList<>(candidateSet);
    }

    private boolean isValidStandingSpot(Block feetBlock) {
        Block groundBlock = feetBlock.getRelative(BlockFace.DOWN);
        Block headBlock = feetBlock.getRelative(BlockFace.UP);
        return groundBlock.getType().isSolid() && !isPassable(groundBlock) && isPassable(feetBlock)
                && isPassable(headBlock);
    }

    private boolean isPassable(Block block) {
        return !block.getType().isSolid() || block.isPassable();
    }

    private double calculateScore(OptimalCollectTarget target) {
        // Lower score is better.
        // 1. Path Cost: The primary factor. Longer paths are worse.
        double pathCost = target.pathToStand().getLength();
        // 2. Mobility Score: A penalty for being in a tight spot.
        int mobilityPenalty = 0;
        Location stand = target.standingLocation();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;
                if (stand.clone().add(dx, 0, dz).getBlock().getType().isSolid()) {
                    mobilityPenalty++;
                }
            }
        }
        final double MOBILITY_WEIGHT = 2.5;
        return pathCost + (mobilityPenalty * MOBILITY_WEIGHT);
    }
}