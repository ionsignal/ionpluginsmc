package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.util.Vector;

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
        // Check for a walkable surface below using NMS
        Block groundBlock = block.getRelative(BlockFace.DOWN);
        Block headBlock = block.getRelative(BlockFace.UP);
        BlockState groundState = ((CraftBlock) groundBlock).getNMS();
        boolean canStandOn = groundState.isFaceSturdy(EmptyBlockGetter.INSTANCE, ((CraftBlock) groundBlock).getPosition(), Direction.UP);
        // || Tag.LEAVES.isTagged(groundBlock.getType());
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
        Material mat = block.getType();
        if (Tag.LEAVES.isTagged(mat)) {
            return false;
        }
        if (mat.isAir() || mat == Material.WATER) {
            return true;
        }
        BlockState state = ((CraftBlock) block).getNMS();
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
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
        // Search down first to find the landing spot.
        Block currentBlock = start.getBlock();
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

    /**
     * Checks for a clear line of sight between two points. This check is robust, using
     * NavigationHelper.isPassable to correctly identify obstacles like leaves and fences, and also
     * detects potential drop-offs.
     *
     * @param from
     *            The starting location (e.g., agent's eyes).
     * @param to
     *            The target location.
     * @param world
     *            The world to perform the check in.
     * @return true if there is a clear, safe line of sight.
     */
    public static boolean hasLineOfSight(Location from, Location to, World world) {
        Location eyeLocation = from.clone().add(0, 1.6, 0); // Approx eye height
        Vector direction = to.toVector().subtract(eyeLocation.toVector());
        double distance = direction.length();
        if (distance < 1.0) {
            return true; // Too close to have an obstacle
        }
        direction.normalize();
        // Raycast step; smaller step for more accuracy in dense environments
        for (double d = 0.5; d < distance; d += 0.5) {
            Location checkLoc = eyeLocation.clone().add(direction.clone().multiply(d));
            // Check if a block is not passable, which blocks line of sight.
            if (!NavigationHelper.isPassable(checkLoc.getBlock())) {
                return false;
            }
            // Check for a drop-off. If the block below our path is passable, it's a cliff
            Location groundCheckLoc = checkLoc.clone().subtract(0, 2.2, 0);
            if (NavigationHelper.isPassable(groundCheckLoc.getBlock())) {
                return false; // Path goes over a ledge, break line of sight.
            }
        }
        return true;
    }
}