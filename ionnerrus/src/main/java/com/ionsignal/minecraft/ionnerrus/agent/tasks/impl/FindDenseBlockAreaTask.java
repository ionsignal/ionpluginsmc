package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class FindDenseBlockAreaTask implements Task {
    // min blocks in a chunk to be considered "dense"
    private static final int DENSITY_THRESHOLD = 20;

    private final Set<Material> materials;
    private final int searchRadius;
    private volatile boolean cancelled = false;

    public FindDenseBlockAreaTask(Set<Material> materials, int searchRadius) {
        this.materials = materials;
        this.searchRadius = searchRadius;
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        this.cancelled = false;
        Location start = agent.getPersona().getLocation();
        return CompletableFuture.supplyAsync(() -> findBestChunk(start), IonNerrus.getInstance().getOffloadThreadExecutor())
                .thenAcceptAsync(bestChunkCenter -> {
                    if (cancelled)
                        return;
                    if (bestChunkCenter != null) {
                        agent.getBlackboard().put(BlackboardKeys.TARGET_LOCATION, bestChunkCenter);
                        // agent.getBlackboard().put(BlackboardKeys.FIND_AREA_RESULT, true); DEPRECATED
                    } else {
                        // agent.getBlackboard().put(BlackboardKeys.FIND_AREA_RESULT, false); DEPRECATED
                    }
                }, IonNerrus.getInstance().getMainThreadExecutor());
    }

    private Location findBestChunk(Location start) {
        World world = start.getWorld();
        if (world == null)
            return null;
        int startChunkX = start.getChunk().getX();
        int startChunkZ = start.getChunk().getZ();
        int chunkRadius = searchRadius / 16;
        for (int r = 1; r <= chunkRadius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (cancelled)
                        return null;
                    if (Math.abs(x) < r && Math.abs(z) < r)
                        continue; // Only check the outer ring
                    Chunk currentChunk = world.getChunkAt(startChunkX + x, startChunkZ + z);
                    if (isChunkDense(currentChunk)) {
                        return currentChunk
                                .getBlock(8, world.getHighestBlockYAt(start.getBlockX() + x * 16 + 8, start.getBlockZ() + z * 16 + 8), 8)
                                .getLocation();
                    }
                }
            }
        }
        return null;
    }

    private boolean isChunkDense(Chunk chunk) {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = chunk.getWorld().getMinHeight(); y < chunk.getWorld().getMaxHeight(); y++) {
                    if (materials.contains(chunk.getBlock(x, y, z).getType())) {
                        count++;
                        if (count >= DENSITY_THRESHOLD) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}