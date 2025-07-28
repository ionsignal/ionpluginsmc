package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class FindNearestBiomeSkill implements Skill<Location> {
    private final Set<Biome> biomes;
    private final int searchRadius;

    public FindNearestBiomeSkill(Set<Biome> biomes, int searchRadius) {
        this.biomes = biomes;
        this.searchRadius = searchRadius;
    }

    @Override
    public CompletableFuture<Location> execute(NerrusAgent agent) {
        Location start = agent.getPersona().getLocation();
        return CompletableFuture.supplyAsync(() -> {
            World world = start.getWorld();
            if (world == null) {
                return null;
            }
            for (int r = 0; r <= searchRadius; r += 16) {
                for (int x = -r; x <= r; x += 16) {
                    for (int z = -r; z <= r; z += 16) {
                        if (Math.abs(x) < r && Math.abs(z) < r && r > 0) {
                            continue;
                        }
                        Location checkLoc = start.clone().add(x, 0, z);
                        if (biomes.contains(world.getBiome(checkLoc))) {
                            return checkLoc;
                        }
                    }
                }
            }
            return null;
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }
}