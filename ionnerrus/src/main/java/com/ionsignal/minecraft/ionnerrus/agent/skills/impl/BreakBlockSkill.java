package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken.Registration;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.BreakBlockResult;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.ActionResult;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.LookResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BreakBlockSkill implements Skill<BreakBlockResult> {
    public static final boolean VISUALIZE_BREAK = true;

    private final Location blockLocation;

    public BreakBlockSkill(Location blockLocation) {
        this.blockLocation = blockLocation;
    }

    @Override
    public CompletableFuture<BreakBlockResult> execute(NerrusAgent agent, ExecutionToken token) {
        return CompletableFuture.supplyAsync(() -> {
            Persona persona = agent.getPersona();
            if (!persona.isSpawned()) {
                return CompletableFuture.completedFuture(BreakBlockResult.failure());
            }
            if (!token.isActive()) {
                return CompletableFuture.completedFuture(BreakBlockResult.failure());
            }
            Block block = blockLocation.getBlock();
            if (block.isEmpty()) {
                return CompletableFuture.completedFuture(BreakBlockResult.alreadyBroken());
            }
            // Check reach using unified reach system
            double reach = persona.getPhysicalBody().state().getBlockReach();
            Location eyeLoc = persona.getLocation().add(0, persona.getPersonaEntity().getEyeHeight(), 0);
            Location targetCenter = blockLocation.clone().add(0.5, 0.5, 0.5);
            if (eyeLoc.distanceSquared(targetCenter) > (reach * reach)) {
                return CompletableFuture.completedFuture(BreakBlockResult.outOfReach());
            }
            // Perform Live Raytrace (Main Thread) using the live server level to ensure we don't try to break
            // through a block that was just placed.
            Level nmsLevel = ((CraftWorld) blockLocation.getWorld()).getHandle();
            Vec3 startVec = new Vec3(eyeLoc.getX(), eyeLoc.getY(), eyeLoc.getZ());
            Vec3 endVec = new Vec3(targetCenter.getX(), targetCenter.getY(), targetCenter.getZ());
            BlockHitResult hitResult = NavigationHelper.rayTrace(nmsLevel, startVec, endVec);
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
            // Setup Event Hook before executing physical break
            return executeWithEventHook(persona, targetCenter, block, token);
        }, IonNerrus.getInstance().getMainThreadExecutor()).thenCompose(f -> f);
    }

    /**
     * Wraps the physical break action with a temporary event listener to capture drops.
     */
    private CompletableFuture<BreakBlockResult> executeWithEventHook(Persona persona, Location lookTarget, Block block,
            ExecutionToken token) {
        // Prepare capture collection and create temporary listener
        List<Item> capturedDrops = new ArrayList<>();
        Listener dropListener = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onBlockDrop(BlockDropItemEvent event) {
                if (event.getBlock().getLocation().equals(blockLocation)) {
                    capturedDrops.addAll(event.getItems());
                }
            }
        };
        // Register listener and bind cleanup to token cancellation to prevent leaks
        Bukkit.getPluginManager().registerEvents(dropListener, IonNerrus.getInstance());
        Registration listenerReg = token.onCancel(() -> HandlerList.unregisterAll(dropListener));
        return executePhysicalBreak(persona, lookTarget, block, token)
                .whenComplete((result, ex) -> {
                    // Cleanup listener on completion (success or failure)
                    HandlerList.unregisterAll(dropListener);
                    listenerReg.close();
                })
                .thenApply(result -> {
                    // Inject captured drops into result if successful
                    if (result.status() == BreakBlockResult.Status.SUCCESS) {
                        return BreakBlockResult.success(capturedDrops);
                    }
                    return result;
                });
    }

    private CompletableFuture<BreakBlockResult> executePhysicalBreak(Persona persona, Location lookTarget, Block block,
            ExecutionToken token) {
        if (VISUALIZE_BREAK) {
            DebugVisualizer.highlightBlock(blockLocation, 10, NamedTextColor.GREEN);
        }
        return persona.getPhysicalBody().orientation().lookAt(lookTarget, true, token)
                .thenCompose(lookResult -> {
                    if (lookResult != LookResult.SUCCESS) {
                        return CompletableFuture.completedFuture(ActionResult.FAILURE);
                    }
                    return persona.getPhysicalBody().actions().breakBlock(block, token);
                })
                .thenApply(result -> result == ActionResult.SUCCESS
                        ? BreakBlockResult.success()
                        : BreakBlockResult.failure());
    }
}