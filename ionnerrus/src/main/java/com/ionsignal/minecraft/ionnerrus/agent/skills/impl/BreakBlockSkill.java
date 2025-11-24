package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.BreakBlockResult;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.ActionResult;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.LookResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.WorldSnapshot;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.concurrent.CompletableFuture;

public class BreakBlockSkill implements Skill<BreakBlockResult> {
    public static final boolean VISUALIZE_BREAK = true;

    private final Location blockLocation;

    public BreakBlockSkill(Location blockLocation) {
        this.blockLocation = blockLocation;
    }

    @Override
    public CompletableFuture<BreakBlockResult> execute(NerrusAgent agent) {
        Persona persona = agent.getPersona();
        Block block = blockLocation.getBlock();
        if (!persona.isSpawned()) {
            return CompletableFuture.completedFuture(BreakBlockResult.failure());
        }
        if (block.isEmpty()) {
            return CompletableFuture.completedFuture(BreakBlockResult.alreadyBroken());
        }
        Location agentEyeLoc = persona.getLocation().add(0, persona.getPersonaEntity().getEyeHeight(), 0);
        Location targetCenter = blockLocation.clone().add(0.5, 0.5, 0.5);
        // Create a snapshot to check for obstructions before committing to the physical action
        BlockPos min = new BlockPos(
                Math.min(agentEyeLoc.getBlockX(), blockLocation.getBlockX()) - 1,
                Math.min(agentEyeLoc.getBlockY(), blockLocation.getBlockY()) - 1,
                Math.min(agentEyeLoc.getBlockZ(), blockLocation.getBlockZ()) - 1);
        BlockPos max = new BlockPos(
                Math.max(agentEyeLoc.getBlockX(), blockLocation.getBlockX()) + 1,
                Math.max(agentEyeLoc.getBlockY(), blockLocation.getBlockY()) + 1,
                Math.max(agentEyeLoc.getBlockZ(), blockLocation.getBlockZ()) + 1);
        return WorldSnapshot.create(persona.getLocation().getWorld(), min, max)
                .thenComposeAsync(snapshot -> {
                    // Perform Raytrace on offload thread using the snapshot
                    BlockHitResult hitResult = snapshot.rayTrace(agentEyeLoc.toVector(), targetCenter.toVector());
                    // Check if we hit something
                    if (hitResult.getType() != HitResult.Type.MISS) {
                        BlockPos hitPos = hitResult.getBlockPos();
                        // If we hit a block that is NOT our target block, we are obstructed.
                        if (hitPos.getX() != blockLocation.getBlockX() ||
                                hitPos.getY() != blockLocation.getBlockY() ||
                                hitPos.getZ() != blockLocation.getBlockZ()) {
                            Location obstructionLoc = new Location(
                                    blockLocation.getWorld(),
                                    hitPos.getX(),
                                    hitPos.getY(),
                                    hitPos.getZ());
                            return CompletableFuture.completedFuture(BreakBlockResult.obstructed(obstructionLoc));
                        }
                    }
                    // Path is clear (or we hit the target), proceed to physical action on main thread
                    return executePhysicalBreak(persona, targetCenter, block);
                }, IonNerrus.getInstance().getMainThreadExecutor());
    }

    private CompletableFuture<BreakBlockResult> executePhysicalBreak(Persona persona, Location lookTarget, Block block) {
        if (VISUALIZE_BREAK) {
            DebugVisualizer.highlightBlock(blockLocation, 10, NamedTextColor.GREEN);
        }
        return persona.getPhysicalBody().orientation().lookAt(lookTarget, true)
                .thenCompose(lookResult -> {
                    if (lookResult != LookResult.SUCCESS) {
                        return CompletableFuture.completedFuture(ActionResult.FAILURE);
                    }
                    return persona.getPhysicalBody().actions().breakBlock(block);
                })
                .thenApply(result -> result == ActionResult.SUCCESS
                        ? BreakBlockResult.success()
                        : BreakBlockResult.failure());
    }
}