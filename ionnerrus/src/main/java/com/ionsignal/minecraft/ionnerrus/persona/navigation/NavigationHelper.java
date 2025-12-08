package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Stateless utility for geometric analysis.
 * Operates on NMS BlockGetter to support both ServerLevel (Sync) and WorldSnapshot (Async).
 */
public final class NavigationHelper {
    // Standard step height for Minecraft entities (Slabs, Carpets, etc.)
    public static final double MAX_STEP_HEIGHT = 0.6;
    // Threshold for checking headroom above partial blocks (approx carpet height + epsilon)
    private static final double HEADROOM_CHECK_THRESHOLD = 0.2;

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
     * Calculates the maximum Y-level of a block's collision shape relative to its base.
     * Returns 0.0 for Air or non-colliding blocks.
     */
    @SuppressWarnings("null")
    public static double getMaxCollisionHeight(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir())
            return 0.0;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty())
            return 0.0;
        return shape.max(Direction.Axis.Y);
    }

    /**
     * Checks if a block can be stepped onto/into (e.g., Carpet, Slab, Air).
     * A block is traversable if its collision height is within step height limits
     * and it is not a fence/wall/leaf.
     */
    @SuppressWarnings("null")
    public static boolean isTraversable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Material mat = state.getBukkitMaterial();
        // Explicitly reject obstacles that might have low collision but are logically impassable
        if (Tag.LEAVES.isTagged(mat) || Tag.FENCES.isTagged(mat) ||
                Tag.WALLS.isTagged(mat) || Tag.FENCE_GATES.isTagged(mat)) {
            // Note: Open fence gates usually have empty collision, handled by getMaxCollisionHeight check below
            // if needed,
            // but Tag check is safer for general logic unless we check block state properties.
            // For now, assume closed. AStarPathfinder can refine this if needed.
            if (Tag.FENCE_GATES.isTagged(mat)) {
                // If collision is empty (Open), it's traversable.
                return getMaxCollisionHeight(level, pos) <= MAX_STEP_HEIGHT;
            }
            return false;
        }
        if (Tag.STAIRS.isTagged(mat)) {
            return true;
        }
        return getMaxCollisionHeight(level, pos) <= MAX_STEP_HEIGHT;
    }

    /**
     * Checks if a block is effectively empty space (Air, Water, Grass, etc).
     * Used for head/body clearance checks.
     */
    public static boolean isClear(BlockGetter level, BlockPos pos) {
        return getMaxCollisionHeight(level, pos) <= 0.0;
    }

    /**
     * Checks if a given block is a valid spot for a Persona to stand on.
     * 
     * - Accepts Partial Blocks (Carpets, Slabs) as the "Feet" block.
     * - Rejects "Floating Grid" nodes (Air directly above Carpet).
     * - Performs dynamic headroom checks based on floor height.
     */
    @SuppressWarnings("null")
    public static boolean isValidStandingSpot(BlockGetter level, BlockPos pos) {
        // 1. Check Ground (Support)
        BlockPos below = pos.below();
        BlockState groundState = level.getBlockState(below);
        // Fast Check: Is the top face sturdy? (Stone, Dirt, Planks)
        boolean isSturdy = groundState.isFaceSturdy(level, below, Direction.UP);
        // Leaves are not sturdy but are valid support
        boolean isLeaves = Tag.LEAVES.isTagged(groundState.getBukkitMaterial());
        // If the ground is NOT sturdy/leaves, it must be a valid collision block (Glass, etc.)
        // BUT, if the ground is "Traversable" (e.g. Carpet), it cannot serve as ground for the block above.
        // This forces the pathfinder to pick the Carpet itself as the node.
        if (!isSturdy && !isLeaves) {
            if (isTraversable(level, below) && isClear(level, pos)) {
                return false; // Prevent floating grid above carpets/slabs
            }
            // Allow standing on Glass/Obsidian/etc which are not "Traversable" (Full height) but maybe not
            // "Sturdy" in some versions
            if (getMaxCollisionHeight(level, below) < 1.0) {
                return false; // Reject standing on weird small blocks that aren't traversable
            }
        }
        // 2. Check Feet
        if (!isTraversable(level, pos)) {
            return false;
        }
        // 3. Check Head
        if (!isClear(level, pos.above())) {
            return false;
        }
        if (Tag.STAIRS.isTagged(level.getBlockState(pos).getBukkitMaterial())) {
            if (!isClear(level, pos.above(2))) {
                return false;
            }
        }
        // 4. Dynamic Headroom Check (The "Concussion" Fix)
        // If standing on a slab (height 0.5), head is at 2.3. Must check pos.above(2).
        // If standing on carpet (height 0.06), head is at 1.86. Fits in pos.above().
        double floorHeight = getMaxCollisionHeight(level, pos);
        if (floorHeight > HEADROOM_CHECK_THRESHOLD) {
            if (!isClear(level, pos.above(2))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a block is passable (Legacy wrapper, prefer isTraversable/isClear).
     */
    public static boolean isPassable(BlockGetter level, BlockPos pos) {
        return isClear(level, pos);
    }

    /**
     * Checks if a block has ANY collision shape.
     */
    public static boolean hasCollision(BlockGetter level, BlockPos pos) {
        return getMaxCollisionHeight(level, pos) > 0.0;
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
        // Check if we are in a water context (Swimming) to adjust obstruction logic
        boolean isSwimming = !level.getFluidState(center).isEmpty();
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
                BlockPos checkPos = center.offset(x, 0, z);
                boolean isObstructed;
                if (isSwimming) {
                    // Obstruction is any aside from water/air and we do not check for ground because we are swimming.
                    isObstructed = hasCollision(level, checkPos);
                } else {
                    // Check for physical obstruction (wall/column) and must be traversable and clear
                    if (!isTraversable(level, checkPos) || !isClear(level, checkPos.above())) {
                        isObstructed = true;
                    } else {
                        // Check Environmental Hazard (Void/Drop)
                        // If physically clear, we must ensure it's a valid place to stand.
                        // This handles voids, lava, or deep drops.
                        isObstructed = !isValidStandingSpot(level, checkPos);
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

    /**
     * Performs a physics-aware sweep to find the actual block supporting the entity.
     * This solves the "Fence/Ledge" problem where integer math fails to find ground.
     *
     * @param level
     *            The snapshot or world.
     * @param entityBounds
     *            The AABB of the entity at its exact current location.
     * @param maxDrop
     *            Maximum distance to check downwards.
     * @return The BlockPos of the supporting block (e.g., the Fence), if found.
     */
    public static Optional<BlockPos> findSupportingBlock(BlockGetter level, AABB entityBounds, double maxDrop) {
        // We sweep the entity's bounding box downwards to find the first collision.
        // We check in increments of 0.5 blocks to ensure we catch thin blocks like trapdoors/slabs.
        double step = 0.5;
        for (double yOffset = 0; yOffset <= maxDrop; yOffset += step) {
            AABB checkBounds = entityBounds.move(0, -yOffset, 0).deflate(0.001, 0.0, 0.001);
            // Check for collision with blocks in this AABB
            int minX = (int) Math.floor(checkBounds.minX);
            int maxX = (int) Math.ceil(checkBounds.maxX);
            int minY = (int) Math.floor(checkBounds.minY);
            int maxY = (int) Math.ceil(checkBounds.maxY);
            int minZ = (int) Math.floor(checkBounds.minZ);
            int maxZ = (int) Math.ceil(checkBounds.maxZ);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        cursor.set(x, y, z);
                        if (hasCollision(level, cursor)) {
                            // Check intersection
                            BlockState state = level.getBlockState(cursor);
                            VoxelShape shape = state.getCollisionShape(level, cursor);
                            if (!shape.isEmpty() && shape.bounds().move(x, y, z).intersects(checkBounds)) {
                                // Found physical support!
                                return Optional.of(cursor.immutable());
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }
}