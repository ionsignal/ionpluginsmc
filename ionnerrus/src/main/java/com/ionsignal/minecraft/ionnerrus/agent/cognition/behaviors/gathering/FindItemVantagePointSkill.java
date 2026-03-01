package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.gathering;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.PathfindingRequest;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.WorldSnapshot;
import com.ionsignal.minecraft.ionnerrus.util.search.ScanOffsets;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * A recovery skill that finds a reachable "Vantage Point" near an unreachable item by acting as a
 * solver and scanning for geometric candidates with valid reachability via AStarPathfinder.
 */
public class FindItemVantagePointSkill implements Skill<Optional<Path>> { // Changed return type
    private static final int SCAN_RADIUS = 3;
    private static final double MAX_LUNGE_DIST = 2.8;
    private static final double MAX_LUNGE_DIST_SQ = MAX_LUNGE_DIST * MAX_LUNGE_DIST;
    private static final int SNAPSHOT_PADDING = 16; // Increased for pathfinding corridor
    private static final int MAX_PATHFINDING_ATTEMPTS = 10; // Limit CPU usage
    private static final double MAX_SEARCH_DISTANCE = 80.0; // Snapshot safety limit

    private final Item targetItem;

    public FindItemVantagePointSkill(Item targetItem) {
        this.targetItem = targetItem;
    }

    @Override
    public CompletableFuture<Optional<Path>> execute(NerrusAgent agent, ExecutionToken token) {
        // Capture Initial State on Main Thread
        if (!targetItem.isValid()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Location itemLoc = targetItem.getLocation();
        Location agentLoc = agent.getPersona().getLocation();
        World world = itemLoc.getWorld();
        // Snapshot Bounds Clamping
        // Prevent creating massive snapshots if the item is extremely far away
        if (agentLoc.distance(itemLoc) > MAX_SEARCH_DISTANCE) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // Capture Agent Dimensions for Pathfinding
        float width = agent.getPersona().getPersonaEntity().getBbWidth();
        float height = agent.getPersona().getPersonaEntity().getBbHeight();
        double eyeHeight = agent.getPersona().getPersonaEntity().getEyeHeight();
        // Define Snapshot Bounds (Corridor between Agent and Item)
        BlockPos startPos = new BlockPos(agentLoc.getBlockX(), agentLoc.getBlockY(), agentLoc.getBlockZ());
        BlockPos targetPos = new BlockPos(itemLoc.getBlockX(), itemLoc.getBlockY(), itemLoc.getBlockZ());
        BlockPos min = new BlockPos(
                Math.min(startPos.getX(), targetPos.getX()) - SNAPSHOT_PADDING,
                Math.min(startPos.getY(), targetPos.getY()) - SNAPSHOT_PADDING,
                Math.min(startPos.getZ(), targetPos.getZ()) - SNAPSHOT_PADDING);
        BlockPos max = new BlockPos(
                Math.max(startPos.getX(), targetPos.getX()) + SNAPSHOT_PADDING,
                Math.max(startPos.getY(), targetPos.getY()) + SNAPSHOT_PADDING,
                Math.max(startPos.getZ(), targetPos.getZ()) + SNAPSHOT_PADDING);
        // Async Execution Chain
        return WorldSnapshot.create(world, min, max)
                .thenApplyAsync(snapshot -> {
                    // Strict Token Check
                    if (!token.isActive())
                        return Optional.empty();
                    // Phase 1: Geometric Scan
                    List<Location> candidates = findCandidates(snapshot, targetPos, itemLoc, world, eyeHeight);
                    // Phase 2: Sort by Proximity to Agent (Greedy Heuristic)
                    candidates.sort(Comparator.comparingDouble(loc -> loc.distanceSquared(itemLoc)));
                    // Phase 3: Pathfinding Validation (Solver Loop)
                    int attempts = 0;
                    for (Location candidate : candidates) {
                        if (attempts >= MAX_PATHFINDING_ATTEMPTS)
                            break;
                        attempts++;
                        // Check cancellation between expensive A* calls
                        if (!token.isActive())
                            return Optional.empty();
                        PathfindingRequest request = new PathfindingRequest(
                                agentLoc,
                                candidate,
                                width,
                                height,
                                NavigationParameters.SHORT_RANGE);
                        // Execute A* synchronously on the offload thread using the shared snapshot
                        Optional<Path> path = AStarPathfinder.computeImmediate(request, snapshot, token);
                        if (path.isPresent()) {
                            return path; // Found a valid path!
                        }
                    }
                    return Optional.empty();
                }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    @SuppressWarnings("null")
    private List<Location> findCandidates(WorldSnapshot snapshot, BlockPos center, Location itemLoc, World world, double eyeHeight) {
        List<Location> validSpots = new ArrayList<>();
        List<BlockPos> offsets = ScanOffsets.getSphere(SCAN_RADIUS);
        // Pre-calculate item center for raytracing
        Vector itemMagnetismTarget = itemLoc.toVector().add(new Vector(0, 0.5, 0));
        for (BlockPos offset : offsets) {
            BlockPos candidatePos = center.offset(offset);
            // Physics Check: Is it a valid place to stand?
            if (!NavigationHelper.isSafeLanding(snapshot, candidatePos)) {
                continue;
            }
            // Proximity Check: Is it close enough to "Lunge" (Fast Mode)?
            Location candidateLoc = new Location(world,
                    candidatePos.getX() + 0.5,
                    candidatePos.getY(),
                    candidatePos.getZ() + 0.5);
            if (candidateLoc.distanceSquared(itemLoc) > MAX_LUNGE_DIST_SQ) {
                continue;
            }
            // Visibility Check: Can we see the item from here?
            // Raytrace from Candidate Eyes to Item Center
            Vector candidateEyes = candidateLoc.toVector().add(new Vector(0, eyeHeight, 0));
            if (hasLineOfSight(snapshot, candidateEyes, itemMagnetismTarget, candidatePos)) {
                validSpots.add(candidateLoc);
                // Dispatch to Main Thread for safe Bukkit API usage
                // IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                // // Highlight the chosen vantage point in Aqua for 2 seconds (40 ticks)
                // com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer.highlightBlock(
                // candidateLoc,
                // 50,
                // net.kyori.adventure.text.format.NamedTextColor.AQUA);
                // });
            } else {
                // Dispatch to Main Thread for safe Bukkit API usage
                // IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                // // Highlight the chosen vantage point in Aqua for 2 seconds (40 ticks)
                // com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer.highlightBlock(
                // candidateLoc,
                // 50,
                // net.kyori.adventure.text.format.NamedTextColor.RED);
                // });
            }

        }
        return validSpots;
    }

    private boolean hasLineOfSight(WorldSnapshot snapshot, Vector start, Vector end, BlockPos originBlock) {
        BlockHitResult hit = snapshot.rayTrace(start, end, ClipContext.Block.COLLIDER);
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        // Self-Occlusion Fix:
        // If the ray hits the block we are standing on (originBlock), ignore it.
        // This happens when looking down over a ledge.
        if (hit.getBlockPos().equals(originBlock)) {
            return true;
        }
        return false;
    }
}