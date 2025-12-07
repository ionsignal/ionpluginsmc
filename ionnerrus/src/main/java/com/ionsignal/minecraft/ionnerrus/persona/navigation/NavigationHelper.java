package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Stateless utility for geometric analysis.
 * Operates on NMS BlockGetter to support both ServerLevel (Sync) and WorldSnapshot (Async).
 */
public final class NavigationHelper {
    private NavigationHelper() {
    }

    /**
     * Performs a ray-trace (clip) to detect physical obstructions.
     *
     * @param level
     *            The world accessor.
     * @param start
     *            The starting vector.
     * @param end
     *            The ending vector.
     * @return The hit result. If type is MISS, the path is clear.
     */
    @SuppressWarnings("null")
    public static BlockHitResult rayTrace(BlockGetter level, Vec3 start, Vec3 end) {
        return level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                CollisionContext.empty()));
    }

    /**
     * Checks if a given block is a valid spot for a Persona to stand on.
     * A valid spot has a walkable surface below and two passable blocks of space for the body.
     */
    @SuppressWarnings("null")
    public static boolean isValidStandingSpot(BlockGetter level, BlockPos pos) {
        // Check for a walkable surface below
        BlockPos below = pos.below();
        BlockState groundState = level.getBlockState(below);
        // Check face solidity
        boolean canStandOn = groundState.isFaceSturdy(level, below, Direction.UP);
        // Fallback for leaves/tags if needed, though collision check usually suffices
        if (!canStandOn && Tag.LEAVES.isTagged(groundState.getBukkitMaterial())) {
            // Leaves are technically standable in Minecraft physics
            canStandOn = true;
        }
        if (!canStandOn) {
            return false;
        }
        // Check for two blocks of passable space for the body and head.
        return isPassable(level, pos) && isPassable(level, pos.above());
    }

    /**
     * Checks if a block is passable for a Persona (e.g., air, water, non-colliding blocks).
     */
    @SuppressWarnings("null")
    public static boolean isPassable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Material mat = state.getBukkitMaterial();
        if (Tag.LEAVES.isTagged(mat)) {
            return false; // Personas treat leaves as obstacles for pathing
        }
        if (state.isAir() || mat == Material.WATER) {
            return true;
        }
        // Use NMS collision shape check
        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty();
    }

    /**
     * Checks if a block has ANY collision shape.
     * This is stricter than !isPassable because it catches things like open trapdoors,
     * fences, and panes which might be technically passable in some contexts but are
     * definitely obstructions for cornering.
     */
    @SuppressWarnings("null")
    public static boolean hasCollision(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Calculates the horizontal clearance radius around a specific node.
     * Used by AStarPathfinder to populate path metadata.
     * 
     * @param level
     *            The world accessor.
     * @param center
     *            The center position to check.
     * @param maxRadius
     *            The maximum radius to check (optimization cap).
     * @return Distance in blocks to the nearest obstruction.
     */
    @SuppressWarnings("null")
    public static double calculateClearance(BlockGetter level, BlockPos center, double maxRadius) {
        double minDistance = maxRadius;
        // Simple radial check in 8 directions + diagonals
        // We check at the feet level and head level
        int checkRadius = (int) Math.ceil(maxRadius);
        for (int x = -checkRadius; x <= checkRadius; x++) {
            for (int z = -checkRadius; z <= checkRadius; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                double dist = Math.sqrt(x * x + z * z);
                if (dist > maxRadius) {
                    continue;
                }
                // Check for environmental hazards (Voids/Drops)
                // If the block is Air (Walking), we must ensure there is ground below.
                // We skip this for Water (Swimming), as deep water is safe.
                BlockPos checkPos = center.offset(x, 0, z);
                boolean isObstructed = !isPassable(level, checkPos) || !isPassable(level, checkPos.above());
                if (!isObstructed) {
                    BlockState state = level.getBlockState(checkPos);
                    if (state.isAir()) {
                        BlockState below = level.getBlockState(checkPos.below());
                        // It is a void if the block below is not sturdy and not leaves
                        boolean hasGround = below.isFaceSturdy(level, checkPos.below(), Direction.UP)
                                || Tag.LEAVES.isTagged(below.getBukkitMaterial());
                        if (!hasGround) {
                            isObstructed = true;
                        }
                    }
                }
                if (isObstructed) {
                    if (dist < minDistance) {
                        minDistance = dist;
                    }
                }
            }
        }
        return minDistance;
    }

    /**
     * Checks if a landing spot is safe for a drop maneuver.
     */
    @SuppressWarnings("null")
    public static boolean isSafeLanding(BlockGetter level, BlockPos pos) {
        // Must be a valid standing spot
        if (!isValidStandingSpot(level, pos)) {
            return false;
        }
        BlockState ground = level.getBlockState(pos.below());
        Material mat = ground.getBukkitMaterial();
        // Avoid obvious hazards
        if (mat == Material.MAGMA_BLOCK || mat == Material.CACTUS || mat == Material.SWEET_BERRY_BUSH) {
            return false;
        }
        // Avoid fluids at the landing spot (unless we can swim, but this is for dropping)
        if (!level.getFluidState(pos).isEmpty()) {
            return false;
        }
        return true;
    }
}