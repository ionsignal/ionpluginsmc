package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.AccessibleBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class FindAccessibleBlockSkill implements Skill<Optional<AccessibleBlockResult>> {
    private final int searchRadius;
    private final int reachableRadius;
    private final int verticalRadius;
    private final Set<Material> materials;
    private final Set<Location> ignoreLocations;

    private static final BlockFace[] NEIGHBOR_FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    public FindAccessibleBlockSkill(Set<Material> materials, int searchRadius, int reachableRadius, int verticalRadius,
            Set<Location> ignoreLocations) {
        this.materials = materials;
        this.searchRadius = searchRadius;
        this.reachableRadius = reachableRadius;
        this.verticalRadius = verticalRadius;
        this.ignoreLocations = ignoreLocations;
    }

    @Override
    public CompletableFuture<Optional<AccessibleBlockResult>> execute(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            Location start = agent.getPersona().getLocation();
            World world = start.getWorld();
            if (world == null) {
                return Optional.empty();
            }
            return findBlock(start, world, agent);
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    private Optional<AccessibleBlockResult> findBlock(Location start, World world, NerrusAgent agent) {
        int x = 0, z = 0;
        int dx = 0, dz = -1;
        int maxI = searchRadius * searchRadius;
        for (int i = 0; i < maxI; i++) {
            if ((-searchRadius / 2 <= x) && (x <= searchRadius / 2) && (-searchRadius / 2 <= z)
                    && (z <= searchRadius / 2)) {
                for (int y = -verticalRadius; y <= verticalRadius; y++) {
                    Block block = world.getBlockAt(start.getBlockX() + x, start.getBlockY() + y, start.getBlockZ() + z);
                    if (!materials.contains(block.getType()) || ignoreLocations.contains(block.getLocation())) {
                        continue;
                    }
                    if (!isCollectable(block)) {
                        continue;
                    }
                    Optional<Location> standingLocationOpt = new FindNearbyReachableSpotSkill(block.getLocation(), reachableRadius)
                            .execute(agent)
                            .join();
                    if (standingLocationOpt.isPresent()) {
                        return Optional.of(new AccessibleBlockResult(block.getLocation(), standingLocationOpt.get()));
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
        return Optional.empty();
    }

    private boolean isCollectable(Block block) {
        for (BlockFace face : NEIGHBOR_FACES) {
            if (!block.getRelative(face).getType().isSolid()) {
                return true;
            }
        }
        return false;
    }
}