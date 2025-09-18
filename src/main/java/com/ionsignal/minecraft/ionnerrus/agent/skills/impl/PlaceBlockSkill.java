package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.NavigateToLocationResult;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus;
import com.ionsignal.minecraft.ionnerrus.persona.action.impl.FaceHeadBodyAction;
import com.ionsignal.minecraft.ionnerrus.persona.animation.PlayerAnimation;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * A skill for an agent to place a block from its inventory into the world.
 */
public class PlaceBlockSkill implements Skill<Optional<Location>> {

    private final Material materialToPlace;
    private final Logger logger;
    // CHANGE START: Add main thread executor for scheduling actions and NMS calls
    private final Executor mainThreadExecutor;
    // CHANGE END

    public PlaceBlockSkill(Material materialToPlace) {
        this.materialToPlace = materialToPlace;
        this.logger = IonNerrus.getInstance().getLogger();
        // CHANGE START
        this.mainThreadExecutor = IonNerrus.getInstance().getMainThreadExecutor();
        // CHANGE END
    }

    @Override
    public CompletableFuture<Optional<Location>> execute(NerrusAgent agent) {
        PlayerInventory inventory = agent.getPersona().getInventory();
        if (inventory == null || !inventory.contains(materialToPlace)) {
            logger.warning("PlaceBlockSkill: Agent does not have " + materialToPlace);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // The logic is now a single, clean CompletableFuture chain.
        // Step 1: Find a suitable spot to place the block (off-thread).
        return findPlacementSpot(agent).thenCompose(placementSpotOpt -> {
            if (placementSpotOpt.isEmpty()) {
                logger.warning("PlaceBlockSkill: Could not find a valid spot to place the block.");
                return CompletableFuture.completedFuture(Optional.empty());
            }
            Location placementSpot = placementSpotOpt.get();
            // Step 2: Find a reachable spot to stand to perform the placement (off-thread).
            return new FindNearbyReachableSpotSkill(placementSpot, 5).execute(agent).thenCompose(standingSpotOpt -> {
                if (standingSpotOpt.isEmpty()) {
                    logger.warning("PlaceBlockSkill: Found a placement spot but no reachable standing spot.");
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                Location standingSpot = standingSpotOpt.get();
                // Step 3: Navigate to the standing spot.
                return new NavigateToLocationSkill(standingSpot, placementSpot).execute(agent).thenComposeAsync(navResult -> {
                    if (navResult != NavigateToLocationResult.SUCCESS) {
                        logger.warning("PlaceBlockSkill: Failed to navigate to standing spot.");
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    // CHANGE START: Correctly schedule and wait for the FaceHeadBodyAction
                    // This block MUST execute on the main thread to safely interact with the ActionController.
                    logger.info("PlaceBlockSkill: Navigation successful. Scheduling face action.");
                    // The constructor is now correct: (Location, turnBody, durationTicks).
                    // durationTicks = -1 means it completes when the agent is facing the target.
                    FaceHeadBodyAction faceAction = new FaceHeadBodyAction(placementSpot, true, -1);
                    agent.getPersona().getActionController().schedule(faceAction);
                    // We now correctly return the future from the action itself. The skill's execution
                    // will pause here until the agent has physically turned to face the spot.
                    return faceAction.getFuture().thenComposeAsync(faceStatus -> {
                        if (faceStatus != ActionStatus.SUCCESS) {
                            logger.warning("PlaceBlockSkill: Failed to face placement spot.");
                            return CompletableFuture.completedFuture(Optional.empty());
                        }
                        // Step 4: Play the placement animation. The main arm swing is used for block placement.
                        agent.getPersona().playAnimation(PlayerAnimation.SWING_MAIN_ARM);
                        // Step 5: Execute the NMS logic to place the block.
                        return placeBlockFromInventory(agent, placementSpot);
                    }, mainThreadExecutor); // Ensure the continuation also runs on the main thread.
                    // CHANGE END

                }, mainThreadExecutor); // The composition after navigation must be on the main thread.
            });
        });
    }

    private CompletableFuture<Optional<Location>> findPlacementSpot(NerrusAgent agent) {
        // This logic is computationally simple but involves Bukkit API, so it's safer to keep it
        // off-thread.
        return CompletableFuture.supplyAsync(() -> {
            Location agentLoc = agent.getPersona().getLocation();
            World world = agentLoc.getWorld();
            if (world == null)
                return Optional.empty();

            // Search in expanding rings for a valid spot.
            for (int r = 1; r <= 5; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        // Only check the outer layer of the ring to avoid redundant checks.
                        if (Math.abs(dx) < r && Math.abs(dz) < r)
                            continue;
                        // Check slightly above, at, and below the agent's current y-level.
                        for (int dy = -1; dy <= 1; dy++) {
                            Block potentialSpot = agentLoc.clone().add(dx, dy, dz).getBlock();
                            Block blockBelow = potentialSpot.getRelative(BlockFace.DOWN);
                            // A valid spot is passable (air, grass) and has a solid block underneath.
                            if (potentialSpot.isPassable() && blockBelow.getType().isSolid()) {
                                return Optional.of(potentialSpot.getLocation());
                            }
                        }
                    }
                }
            }
            return Optional.empty();
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    private CompletableFuture<Optional<Location>> placeBlockFromInventory(NerrusAgent agent, Location location) {
        // This entire method involves NMS and inventory manipulation, so it must run on the main thread.
        // The supplyAsync call with the mainThreadExecutor ensures this.
        return CompletableFuture.supplyAsync(() -> {
            PersonaEntity personaEntity = agent.getPersona().getPersonaEntity();
            PlayerInventory inventory = agent.getPersona().getInventory();
            if (personaEntity == null || inventory == null)
                return Optional.empty();
            int slot = inventory.first(materialToPlace);
            if (slot == -1)
                return Optional.empty();
            org.bukkit.inventory.ItemStack bukkitStack = inventory.getItem(slot);
            if (bukkitStack == null)
                return Optional.empty();
            // Convert to NMS ItemStack
            ItemStack nmsStack = CraftItemStack.asNMSCopy(bukkitStack);
            Block blockBelow = location.getBlock().getRelative(BlockFace.DOWN);
            BlockPos posBelow = new BlockPos(blockBelow.getX(), blockBelow.getY(), blockBelow.getZ());
            // Create a fake "hit result" as if the player right-clicked the top face of the block below.
            BlockHitResult hitResult = new BlockHitResult(
                    new Vec3(location.getX(), location.getY() - 1, location.getZ()),
                    Direction.UP, // The face that was "clicked"
                    posBelow,
                    false);
            // CHANGE START: Correct the UseOnContext constructor call.
            // It requires the world (Level) as the first parameter.
            UseOnContext useOnContext = new UseOnContext(personaEntity.level(), personaEntity, InteractionHand.MAIN_HAND, nmsStack,
                    hitResult);
            // CHANGE END
            // CHANGE START: Correct the method signature for `useOn`.
            // The method only takes the UseOnContext object; the hand is already part of the context.
            nmsStack.useOn(useOnContext);
            // CHANGE END
            // Verify that the block was actually placed.
            if (location.getBlock().getType() == materialToPlace) {
                logger.info("Successfully placed " + materialToPlace + " at " + location.toVector());
                return Optional.of(location);
            } else {
                logger.warning("Failed to place " + materialToPlace + " at " + location.toVector() + ". The block is now "
                        + location.getBlock().getType());
                // It's possible the stack was consumed but placement failed. We don't handle that edge case yet.
                return Optional.empty();
            }
        }, mainThreadExecutor);
    }
}