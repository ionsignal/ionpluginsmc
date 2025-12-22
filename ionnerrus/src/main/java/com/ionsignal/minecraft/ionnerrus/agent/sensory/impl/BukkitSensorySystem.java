package com.ionsignal.minecraft.ionnerrus.agent.sensory.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.SensoryConfig;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.SensoryEntity;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.SensorySystem;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.WorkingMemory;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of the SensorySystem for the Bukkit/Paper environment.
 * Implements the "Fast Gather / Async Filter" pattern to minimize main thread impact.
 */
public class BukkitSensorySystem implements SensorySystem {
    private final NerrusAgent agent;
    private final AtomicReference<WorkingMemory> memoryRef;

    private volatile SensoryConfig config;
    private volatile boolean isScanInProgress = false;

    // Concurrency Control
    private long lastScanStartTime = 0;

    // Snapshot record for thread safety
    private record SensorySnapshot(UUID uuid, EntityType type, Location location, Vector velocity, boolean isLiving) {
    }

    // Use SensorySnapshot instead of Entity
    private record ScanInput(Location agentEyeLocation, Vector agentDirection, List<SensorySnapshot> snapshots, long gameTime) {
    }

    public BukkitSensorySystem(NerrusAgent agent) {
        this.agent = agent;
        this.config = SensoryConfig.DEFAULT;
        this.memoryRef = new AtomicReference<>(WorkingMemory.EMPTY);
    }

    @Override
    public void tick() {
        // Prevent stacking scans if the async thread is falling behind
        if (isScanInProgress) {
            // Safety valve: If scan takes > 2 seconds, assume it crashed/stalled and reset.
            if (System.currentTimeMillis() - lastScanStartTime > 2000) {
                IonNerrus.getInstance().getLogger().warning("Sensory scan for " + agent.getName() + " hanging! Resetting.");
                isScanInProgress = false;
            }
            return;
        }
        if (!agent.getPersona().isSpawned()) {
            return;
        }
        isScanInProgress = true;
        lastScanStartTime = System.currentTimeMillis();
        // Main thread gather (Fast, minimal logic)
        ScanInput input = gatherRawData();
        // Async process heavy math and sorting
        CompletableFuture.supplyAsync(() -> processAsync(input), IonNerrus.getInstance().getOffloadThreadExecutor())
                .thenAcceptAsync(this::completeScan, IonNerrus.getInstance().getMainThreadExecutor()) // Back to main for atomic swap
                .exceptionally(ex -> {
                    IonNerrus.getInstance().getLogger().severe("Error in sensory process for " + agent.getName() + ": " + ex.getMessage());
                    isScanInProgress = false; // Release lock on error
                    return null;
                });
    }

    @Override
    public WorkingMemory getWorkingMemory() {
        return memoryRef.get();
    }

    @Override
    public boolean isStale() {
        long age = System.currentTimeMillis() - memoryRef.get().timestamp();
        return age > config.memoryRetentionMs();
    }

    @Override
    public void configure(SensoryConfig config) {
        this.config = config;
    }

    /**
     * Quickly collect raw Bukkit entities and agent state on main thread.
     */
    private ScanInput gatherRawData() {
        Entity bukkitEntity = agent.getPersona().getEntity();
        if (bukkitEntity == null) {
            return new ScanInput(null, null, List.of(), 0);
        }
        Location eyeLoc = ((LivingEntity) bukkitEntity).getEyeLocation();
        Vector direction = eyeLoc.getDirection();
        long time = bukkitEntity.getWorld().getTime();
        // Get nearby entities within view distance
        List<Entity> nearby = bukkitEntity.getNearbyEntities(
                config.viewDistanceBlocks(),
                config.viewDistanceBlocks(),
                config.viewDistanceBlocks());
        // Convert to snapshots to detach from live NMS entities
        List<SensorySnapshot> snapshots = new ArrayList<>(nearby.size());
        for (Entity e : nearby) {
            snapshots.add(new SensorySnapshot(
                    e.getUniqueId(),
                    e.getType(),
                    e.getLocation().clone(),
                    e.getVelocity().clone(),
                    e instanceof LivingEntity));
        }
        return new ScanInput(eyeLoc, direction, snapshots, time);
    }

    /**
     * Processes the raw entities into immutable SensoryEntity records in offload thread.
     */
    private WorkingMemory processAsync(ScanInput input) {
        if (input.agentEyeLocation == null) {
            return WorkingMemory.EMPTY;
        }
        List<SensoryEntity> visibleEntities = new ArrayList<>();
        SensoryEntity bestTarget = null;
        double closestDistSq = Double.MAX_VALUE;
        double fovCos = Math.cos(Math.toRadians(config.fieldOfViewDegrees() / 2.0));
        // Iterate snapshots instead of raw entities
        for (SensorySnapshot snapshot : input.snapshots) {
            // Filter out self (just in case)
            if (snapshot.uuid().equals(agent.getPersona().getUniqueId()))
                continue;
            Location targetLoc = snapshot.location(); // Safe clone
            Vector toTarget = targetLoc.toVector().subtract(input.agentEyeLocation.toVector());
            double distSq = toTarget.lengthSquared();
            // Normalize for dot product
            Vector toTargetNorm = toTarget.clone().normalize();
            double dot = input.agentDirection.dot(toTargetNorm);
            // FOV Check
            boolean isVisible = dot >= fovCos;
            // Create immutable snapshot
            SensoryEntity sensoryEntity = new SensoryEntity(
                    snapshot.uuid(),
                    snapshot.type(),
                    targetLoc,
                    snapshot.velocity(),
                    isVisible);
            if (isVisible) {
                visibleEntities.add(sensoryEntity);
                // Simple attention logic: Closest visible LivingEntity
                if (snapshot.isLiving() && distSq < closestDistSq) {
                    closestDistSq = distSq;
                    bestTarget = sensoryEntity;
                }
            }
        }
        return new WorkingMemory(visibleEntities, Optional.ofNullable(bestTarget),
                input.gameTime,
                System.currentTimeMillis());
    }

    /**
     * Update the atomic reference and releases the scan lock on main thread.
     */
    private void completeScan(WorkingMemory newMemory) {
        if (newMemory != null) {
            memoryRef.set(newMemory);
        }
        isScanInProgress = false;
    }
}