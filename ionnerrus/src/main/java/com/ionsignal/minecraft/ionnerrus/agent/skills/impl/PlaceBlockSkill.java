package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.NavigateToLocationResult;
import com.ionsignal.minecraft.ionnerrus.persona.components.PhysicalBody;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.ActionResult;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.LookResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.WorldSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.PlayerInventory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * A skill for an agent to place a block from its inventory into the world.
 */
public class PlaceBlockSkill implements Skill<Optional<Location>> {

    private final Material materialToPlace;
    private final Logger logger;

    public PlaceBlockSkill(Material materialToPlace) {
        this.materialToPlace = materialToPlace;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public CompletableFuture<Optional<Location>> execute(NerrusAgent agent, ExecutionToken token) {
        PlayerInventory inventory = agent.getPersona().getInventory();
        if (inventory == null || !inventory.contains(materialToPlace)) {
            logger.warning("PlaceBlockSkill: Agent does not have " + materialToPlace);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // Capture state and use WorldSnapshot
        final Location agentLoc = agent.getPersona().getLocation();
        final World world = agentLoc.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // Define snapshot bounds (Radius 5 + padding)
        BlockPos center = new BlockPos(agentLoc.getBlockX(), agentLoc.getBlockY(), agentLoc.getBlockZ());
        BlockPos min = center.offset(-6, -3, -6);
        BlockPos max = center.offset(6, 3, 6);
        // Create snapshot -> Find spot (Async) -> Process result
        return WorldSnapshot.create(world, min, max)
                .thenCompose(snapshot -> findPlacementSpot(agentLoc, snapshot)) // Pass captured loc and snapshot
                .thenCompose(placementSpotOpt -> {
                    // THREADING FIX END
                    if (placementSpotOpt.isEmpty()) {
                        logger.warning("PlaceBlockSkill: Could not find a valid spot to place the block.");
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    Location placementSpot = placementSpotOpt.get();
                    // Find a reachable spot to stand to perform the placement (off-thread).
                    return new FindNearbyReachableSpotSkill(placementSpot, 5).execute(agent, token).thenCompose(standingSpotOpt -> {
                        if (standingSpotOpt.isEmpty()) {
                            logger.warning("PlaceBlockSkill: Found a placement spot but no reachable standing spot.");
                            return CompletableFuture.completedFuture(Optional.empty());
                        }
                        Location standingSpot = standingSpotOpt.get();
                        // Navigate to the standing spot.
                        return new NavigateToLocationSkill(standingSpot, placementSpot).execute(agent, token)
                                .thenComposeAsync(navResult -> {
                                    if (navResult != NavigateToLocationResult.SUCCESS) {
                                        logger.warning("PlaceBlockSkill: Failed to navigate to standing spot.");
                                        return CompletableFuture.completedFuture(Optional.empty());
                                    }
                                    logger.info("PlaceBlockSkill: Navigation successful. Performing placement.");
                                    PhysicalBody body = agent.getPersona().getPhysicalBody();
                                    // Look at the spot
                                    return body.orientation().lookAt(placementSpot, true).thenCompose(lookResult -> {
                                        if (lookResult != LookResult.SUCCESS) {
                                            logger.warning("PlaceBlockSkill: Failed to face placement spot.");
                                            return CompletableFuture.completedFuture(Optional.empty());
                                        }
                                        // Delegate to the PhysicalBody to perform the action
                                        return body.actions().placeBlock(materialToPlace, placementSpot, token).thenApply(actionResult -> {
                                            if (actionResult == ActionResult.SUCCESS) {
                                                return Optional.of(placementSpot);
                                            } else {
                                                logger.warning("PlaceBlockSkill: Placement action failed.");
                                                return Optional.empty();
                                            }
                                        });
                                    });
                                }, IonNerrus.getInstance().getMainThreadExecutor());
                    });
                });
    }

    @SuppressWarnings("null")
    private CompletableFuture<Optional<Location>> findPlacementSpot(Location agentLoc, WorldSnapshot snapshot) {
        // Logic is now safe to run off-thread because it uses the immutable snapshot and captured location
        return CompletableFuture.supplyAsync(() -> {
            // No live Bukkit API calls here!
            // Search in expanding rings for a valid spot.
            for (int r = 1; r <= 5; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        // Only check the outer layer of the ring to avoid redundant checks.
                        if (Math.abs(dx) < r && Math.abs(dz) < r)
                            continue;
                        // Check slightly above, at, and below the agent's current y-level.
                        for (int dy = -1; dy <= 1; dy++) {
                            // Use NMS BlockPos math
                            BlockPos potentialPos = new BlockPos(
                                    agentLoc.getBlockX() + dx,
                                    agentLoc.getBlockY() + dy,
                                    agentLoc.getBlockZ() + dz);
                            BlockPos posBelow = potentialPos.below();
                            BlockState state = snapshot.getBlockState(potentialPos);
                            BlockState stateBelow = snapshot.getBlockState(posBelow);
                            if (state == null || stateBelow == null)
                                continue; // Out of snapshot bounds
                            // Check 1: Is the target spot passable? (Air, grass, etc.)
                            // We check if the collision shape is empty.
                            boolean isPassable = state.getCollisionShape(EmptyBlockGetter.INSTANCE, potentialPos).isEmpty();
                            // Check 2: Is there a solid block underneath to place on?
                            // We check if the collision shape is NOT empty.
                            boolean isSolidBelow = !stateBelow.getCollisionShape(EmptyBlockGetter.INSTANCE, posBelow).isEmpty();
                            if (isPassable && isSolidBelow) {
                                // Reconstruct Bukkit Location for the result
                                return Optional.of(new Location(
                                        agentLoc.getWorld(),
                                        potentialPos.getX(),
                                        potentialPos.getY(),
                                        potentialPos.getZ()));
                            }
                        }
                    }
                }
            }
            return Optional.empty();
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }
}