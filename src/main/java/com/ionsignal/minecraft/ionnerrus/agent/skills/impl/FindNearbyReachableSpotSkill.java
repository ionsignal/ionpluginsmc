package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

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
     * @param targetLocation The center of the search area.
     * @param searchRadius The radius for the search in all three dimensions (x, y, z).
     */
    public FindNearbyReachableSpotSkill(Location targetLocation, int searchRadius) {
        this(targetLocation, searchRadius, searchRadius);
    }

    /**
     * Constructs the skill with a cuboid search area.
     * @param targetLocation The center of the search area.
     * @param searchRadius The horizontal radius (x, z).
     * @param verticalRadius The vertical radius (y).
     */
    public FindNearbyReachableSpotSkill(Location targetLocation, int searchRadius, int verticalRadius) {
        this.targetLocation = targetLocation;
        this.searchRadius = searchRadius;
        this.verticalRadius = verticalRadius;
    }

    @Override
    public CompletableFuture<Optional<Location>> execute(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            Persona persona = agent.getPersona();
            World world = targetLocation.getWorld();
            if (world == null) {
                return Optional.empty();
            }

            // Search in a cube around the target
            List<Location> candidates = new ArrayList<>();
            Location targetCenter = targetLocation.clone().add(0.5, 0.5, 0.5);
            int startX = targetLocation.getBlockX();
            int startY = targetLocation.getBlockY();
            int startZ = targetLocation.getBlockZ();
            for (int x = startX - searchRadius; x <= startX + searchRadius; x++) {
                for (int z = startZ - searchRadius; z <= startZ + searchRadius; z++) {
                    for (int y = startY - verticalRadius; y <= startY + verticalRadius; y++) {
                        Block feetBlock = world.getBlockAt(x, y, z);
                        Block standBlock = feetBlock.getRelative(BlockFace.DOWN);
                        Block bodyBlock = feetBlock.getRelative(BlockFace.UP);

                        if (isSolid(standBlock) && isPassable(feetBlock) && isPassable(bodyBlock)) {
                            Location standingSpot = feetBlock.getLocation().add(0.5, 0, 0.5);
                            if (standingSpot.distanceSquared(targetCenter) <= MAX_REACH_SQUARED) {
                                candidates.add(standingSpot);
                            }
                        }
                    }
                }
            }
            if (candidates.isEmpty()) {
                return Optional.empty();
            }

            // Sort by distance to the agent to find the closest reachable spot
            Location agentLocation = persona.getLocation();
            candidates.sort(Comparator.comparingDouble(loc -> loc.distanceSquared(agentLocation)));

            // Find the first one that is actually pathable
            for (Location candidate : candidates) {
                boolean canNavigate = AStarPathfinder.findPath(persona.getLocation(), candidate, NavigationParameters.DEFAULT)
                        .join().isPresent();
                if (canNavigate) {
                    return Optional.of(candidate);
                }
            }

            return Optional.empty();
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    private boolean isSolid(Block block) {
        return block.getType().isSolid() && !block.isLiquid();
    }

    private boolean isPassable(Block block) {
        return !block.getType().isSolid() && !block.isLiquid();
    }
}