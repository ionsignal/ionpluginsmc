package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.BreakBlockResult;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus;
import com.ionsignal.minecraft.ionnerrus.persona.action.impl.BlockBreakerAction;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;

// import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
// import org.bukkit.util.RayTraceResult;
// import org.bukkit.util.Vector;

import java.util.concurrent.CompletableFuture;

public class BreakBlockSkill implements Skill<BreakBlockResult> {
    // Added constants for configuration and physics
    public static final boolean VISUALIZE_BREAK = true;
    // private static final double MAX_REACH_DISTANCE_SQUARED = 6.0 * 6.0; // A generous reach distance

    private final Location blockLocation;

    public BreakBlockSkill(Location blockLocation) {
        this.blockLocation = blockLocation;
    }

    @Override
    public CompletableFuture<BreakBlockResult> execute(NerrusAgent agent) {
        Persona persona = agent.getPersona();
        Block block = blockLocation.getBlock();
        if (!persona.isSpawned()) {
            return CompletableFuture.completedFuture(BreakBlockResult.ACTION_FAILED);
        }
        if (block.isEmpty()) {
            return CompletableFuture.completedFuture(BreakBlockResult.ALREADY_BROKEN);
        }
        // Location agentLocation = persona.getLocation();
        // Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
        // // Added Reach Check
        // if (agentLocation.distanceSquared(blockCenter) > MAX_REACH_DISTANCE_SQUARED) {
        // return CompletableFuture.completedFuture(BreakBlockResult.OUT_OF_REACH);
        // }
        // // Added Line of Sight Check
        // Location eyeLocation = persona.getEntity().getEyeLocation();
        // Vector direction = blockCenter.toVector().subtract(eyeLocation.toVector());
        // if (direction.lengthSquared() > 0) { // Avoid normalization of zero vector
        // double distance = direction.length();
        // direction.normalize();
        // RayTraceResult rayTrace = eyeLocation.getWorld().rayTraceBlocks(
        // eyeLocation,
        // direction,
        // distance,
        // FluidCollisionMode.NEVER,
        // true // ignorePassableBlocks = true
        // );
        // if (rayTrace != null && rayTrace.getHitBlock() != null && !rayTrace.getHitBlock().equals(block))
        // {
        // if (VISUALIZE_BREAK) {
        // DebugVisualizer.highlightBlock(rayTrace.getHitBlock().getLocation(), 10, NamedTextColor.RED);
        // }
        // return CompletableFuture.completedFuture(BreakBlockResult.OBSTRUCTED);
        // }
        // }
        if (VISUALIZE_BREAK) {
            DebugVisualizer.highlightBlock(blockLocation, 10, NamedTextColor.GREEN);
        }
        BlockBreakerAction action = new BlockBreakerAction(block);
        persona.getActionController().schedule(action);
        return action.getFuture()
                .thenApply(status -> status == ActionStatus.SUCCESS ? BreakBlockResult.SUCCESS : BreakBlockResult.ACTION_FAILED);
    }
}