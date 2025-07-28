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
import java.util.concurrent.CompletableFuture;

public class FindStandingLocationSkill implements Skill<Location> {
    private final Location targetLocation;

    public FindStandingLocationSkill(Location targetLocation) {
        this.targetLocation = targetLocation;
    }

    @Override
    public CompletableFuture<Location> execute(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            return findBestStandingLocation(this.targetLocation, agent);
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    private Location findBestStandingLocation(Location targetBlockLocation, NerrusAgent agent) {
        final int searchRadius = 6; // full *reach*
        final int verticalRadius = 6; // full *reach*
        final double maxReachSquared = 35; // this would be maximum for a fully maxed *reach* AI

        Persona persona = agent.getPersona();
        World world = targetBlockLocation.getWorld();
        if (world == null)
            return null;

        List<Location> candidates = new ArrayList<Location>();
        int startX = targetBlockLocation.getBlockX();
        int startY = targetBlockLocation.getBlockY();
        int startZ = targetBlockLocation.getBlockZ();

        for (int x = startX - searchRadius; x <= startX + searchRadius; x++) {
            for (int z = startZ - searchRadius; z <= startZ + searchRadius; z++) {
                for (int y = startY - verticalRadius; y <= startY + verticalRadius; y++) {
                    Block feetBlock = world.getBlockAt(x, y, z);
                    Block standBlock = feetBlock.getRelative(BlockFace.DOWN);
                    Block bodyBlock = feetBlock.getRelative(BlockFace.UP);
                    if (isSolid(standBlock) && isPassable(feetBlock) && isPassable(bodyBlock)) {
                        Location standingSpot = feetBlock.getLocation().add(0.5, 0, 0.5);
                        if (standingSpot.distanceSquared(targetBlockLocation.clone().add(0.5, 0.5, 0.5)) <= maxReachSquared) {
                            candidates.add(standingSpot);
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        Location location = persona.getLocation();
        candidates.sort(Comparator.comparingDouble(loc -> loc.distanceSquared(location)));
        for (Location candidate : candidates) {
            boolean canNavigate = AStarPathfinder.findPath(persona.getLocation(), candidate, NavigationParameters.DEFAULT)
                    .join().isPresent();
            if (canNavigate) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isSolid(Block block) {
        return block.getType().isSolid() && !block.isLiquid();
    }

    private boolean isPassable(Block block) {
        return !block.getType().isSolid() && !block.isLiquid();
    }
}
