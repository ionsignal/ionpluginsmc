package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.PathfindingRequest;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.WorldSnapshot;

import net.minecraft.core.BlockPos;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A skill to find the closest, pathable standing location within a given radius of a target point.
 * A "standing location" is a 2-block high passable space on top of a solid block.
 */
public class FindNearbyReachableSpotSkill implements Skill<Optional<Location>> {
    private final Location targetLocation;
    private final int searchRadius;
    private final int verticalRadius;
    private static final double MAX_REACH_SQUARED = 6.0 * 6.0; // 36, a generous 6-block reach.

    /**
     * Constructs the skill with a cubic search area.
     * 
     * @param targetLocation
     *            The center of the search area.
     * @param searchRadius
     *            The radius for the search in all three dimensions (x, y, z).
     */
    public FindNearbyReachableSpotSkill(Location targetLocation, int searchRadius) {
        this(targetLocation, searchRadius, searchRadius);
    }

    /**
     * Constructs the skill with a cuboid search area.
     * 
     * @param targetLocation
     *            The center of the search area.
     * @param searchRadius
     *            The horizontal radius (x, z).
     * @param verticalRadius
     *            The vertical radius (y).
     */
    public FindNearbyReachableSpotSkill(Location targetLocation, int searchRadius, int verticalRadius) {
        this.targetLocation = targetLocation;
        this.searchRadius = searchRadius;
        this.verticalRadius = verticalRadius;
    }

    @Override
    public CompletableFuture<Optional<Location>> execute(NerrusAgent agent, ExecutionToken token) {
        // Capture state on main thread to ensure thread safety
        final Location startLocation = agent.getPersona().getLocation();
        final float width = agent.getPersona().getPersonaEntity().getBbWidth();
        final float height = agent.getPersona().getPersonaEntity().getBbHeight();
        final World world = targetLocation.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // Calculate snapshot bounds for the search area
        BlockPos center = new BlockPos(targetLocation.getBlockX(), targetLocation.getBlockY(), targetLocation.getBlockZ());
        BlockPos min = center.offset(-searchRadius, -verticalRadius, -searchRadius);
        BlockPos max = center.offset(searchRadius, verticalRadius, searchRadius);
        // Create snapshot asynchronously, then process on offload thread
        return WorldSnapshot.create(world, min, max)
                .thenApplyAsync(snapshot -> {
                    List<Location> candidates = new ArrayList<>();
                    int startX = targetLocation.getBlockX();
                    int startY = targetLocation.getBlockY();
                    int startZ = targetLocation.getBlockZ();
                    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                    // Scan the area for valid standing spots
                    for (int x = startX - searchRadius; x <= startX + searchRadius; x++) {
                        for (int z = startZ - searchRadius; z <= startZ + searchRadius; z++) {
                            for (int y = startY - verticalRadius; y <= startY + verticalRadius; y++) {
                                cursor.set(x, y, z);
                                // Use NavigationHelper to check if it's a valid spot (Solid below, Air/Passable at feet & head)
                                if (NavigationHelper.isValidStandingSpot(snapshot, cursor)) {
                                    // Check if this spot is close enough to interact with the target
                                    // We use the center of the block for distance calculation
                                    double distSq = cursor.distToCenterSqr(targetLocation.getX(), targetLocation.getY(),
                                            targetLocation.getZ());
                                    if (distSq <= MAX_REACH_SQUARED) {
                                        candidates.add(new Location(world, x + 0.5, y, z + 0.5));
                                    }
                                }
                            }
                        }
                    }
                    if (candidates.isEmpty()) {
                        return Optional.empty();
                    }
                    // Sort by distance to the target to find the closest reachable spot
                    candidates.sort(Comparator.comparingDouble(loc -> loc.distanceSquared(targetLocation)));
                    // Find the first one that is actually pathable
                    for (Location candidate : candidates) {
                        if (!token.isActive()) {
                            break;
                        }
                        PathfindingRequest request = new PathfindingRequest(
                                startLocation,
                                candidate,
                                width,
                                height,
                                NavigationParameters.DEFAULT);
                        try {
                            // We join here because we are already on an offload thread and need to check sequentially.
                            // AStarPathfinder.findPath handles its own threading internally but returns a future.
                            boolean canNavigate = AStarPathfinder.findPath(request, token)
                                    .join().isPresent();
                            if (canNavigate) {
                                return Optional.of(candidate);
                            }
                        } catch (Exception e) {
                            // Ignore pathfinding errors for individual candidates and continue searching
                        }
                    }
                    return Optional.empty();
                }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }
}