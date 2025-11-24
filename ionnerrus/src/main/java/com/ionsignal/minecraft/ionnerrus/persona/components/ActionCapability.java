package com.ionsignal.minecraft.ionnerrus.persona.components;

import com.ionsignal.minecraft.ionnerrus.persona.animation.PlayerAnimation;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.ActionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import java.util.concurrent.CompletableFuture;

/**
 * Controls block/entity interactions.
 * Key invariant: Actions SERIALIZE with each other (no concurrent actions).
 */
public interface ActionCapability {
    /**
     * Breaks a block in the world.
     *
     * Preconditions:
     * - Block must be within reach
     * - Line of sight must be clear
     *
     * Lifecycle:
     * - Main thread: Break animation loop
     * - Completion: When block.getType() == AIR
     *
     * @param target
     *            The block to break.
     * @return A future with the result of the break operation.
     */
    CompletableFuture<ActionResult> breakBlock(Block target);

    /**
     * Places a block from inventory.
     *
     * Preconditions:
     * - Material must be in inventory
     * - Target location must be valid placement spot
     *
     * @param material
     *            The material to place.
     * @param target
     *            The location to place the block.
     * @return A future with the result of the placement.
     */
    CompletableFuture<ActionResult> placeBlock(Material material, Location target);

    /**
     * Swaps items between two slots in the inventory.
     *
     * @param sourceSlot
     *            The slot index to move from.
     * @param destinationSlot
     *            The slot index to move to.
     * @return A future with the result of the swap.
     */
    CompletableFuture<ActionResult> swapItems(int sourceSlot, int destinationSlot);

    /**
     * Plays a visual animation on the persona.
     * This is considered a "fire-and-forget" action and may not block other actions.
     *
     * @param animation
     *            The animation to play.
     */
    void playAnimation(PlayerAnimation animation);

    /**
     * Cancels active action (if any).
     */
    void cancelAction();

    /**
     * Checks if an action is currently in progress.
     * 
     * @return true if busy.
     */
    boolean isBusy();
}