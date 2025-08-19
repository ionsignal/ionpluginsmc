package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.block.CraftBlock;

import java.util.Optional;

public final class NavigationHelper {
    private NavigationHelper() {
    }

    /**
     * Checks if a given block is a valid spot for a Persona to stand on.
     * A valid spot has a walkable surface below and two passable blocks of space for the body.
     *
     * @param block
     *            The block representing the feet location.
     * @return true if the location is a valid standing spot.
     */
    public static boolean isValidStandingSpot(Block block) {
        Block groundBlock = block.getRelative(BlockFace.DOWN);
        Block headBlock = block.getRelative(BlockFace.UP);
        // Check for a walkable surface below using NMS for accuracy.
        // This logic should mirror AStarPathfinder's check for consistency.
        BlockState groundState = ((CraftBlock) groundBlock).getNMS();
        boolean canStandOn = groundState.isFaceSturdy(EmptyBlockGetter.INSTANCE, ((CraftBlock) groundBlock).getPosition(), Direction.UP)
                || Tag.LEAVES.isTagged(groundBlock.getType());
        if (!canStandOn) {
            return false;
        }
        // Check for two blocks of passable space for the body and head.
        return isPassable(block) && isPassable(headBlock);
    }

    /**
     * Checks if a block is passable for a Persona (e.g., air, water, non-colliding blocks).
     *
     * @param block
     *            The block to check.
     * @return true if the block is passable.
     */
    public static boolean isPassable(Block block) {
        // This logic should mirror AStarPathfinder's isAirOrPassable check.
        Material mat = block.getType();
        if (Tag.LEAVES.isTagged(mat)) {
            return false; // Personas cannot pass through leaves.
        }
        if (mat.isAir() || mat == Material.WATER) {
            return true;
        }
        // Use NMS collision shape as the definitive check for passability.
        BlockState state = ((CraftBlock) block).getNMS();
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).isEmpty();
    }

    /**
     * Finds the nearest valid ground location by searching vertically from a starting point.
     * It searches downwards first, then upwards.
     *
     * @param start
     *            The starting location for the search.
     * @param searchRange
     *            The maximum vertical distance to search in each direction.
     * @return An Optional containing the ground Location if found, otherwise an empty Optional.
     */
    public static Optional<Location> findGround(Location start, int searchRange) {
        Block currentBlock = start.getBlock();
        // Search down first to find the landing spot.
        for (int i = 0; i < searchRange; i++) {
            if (isValidStandingSpot(currentBlock)) {
                // Return the center of the block.
                return Optional.of(currentBlock.getLocation().add(0.5, 0, 0.5));
            }
            currentBlock = currentBlock.getRelative(BlockFace.DOWN);
        }
        // If not found, search up from the original start point.
        currentBlock = start.getBlock().getRelative(BlockFace.UP);
        for (int i = 0; i < searchRange; i++) {
            if (isValidStandingSpot(currentBlock)) {
                return Optional.of(currentBlock.getLocation().add(0.5, 0, 0.5));
            }
            currentBlock = currentBlock.getRelative(BlockFace.UP);
        }
        return Optional.empty(); // No valid ground found in range.
    }
}