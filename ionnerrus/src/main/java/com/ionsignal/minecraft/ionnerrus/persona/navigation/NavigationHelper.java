package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.bukkit.Location;
import org.bukkit.Material;

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
     * Checks if a block can be stepped onto/into (e.g., Carpet, Slab, Air) using BlockClassification
     * for semantic clarity.
     */
    public static boolean isTraversable(BlockGetter level, BlockPos pos) {
        return BlockClassification.classify(level, pos) == BlockClassification.TRAVERSABLE;
    }

    /**
     * Checks if a block is effectively empty space (Air, Grass, etc) using BlockClassification to
     * handle Phantom blocks (Vegetation).
     */
    public static boolean isClear(BlockGetter level, BlockPos pos) {
        BlockClassification type = BlockClassification.classify(level, pos);
        return type == BlockClassification.OPEN || type == BlockClassification.PHANTOM || type == BlockClassification.FLUID;
    }

    /**
     * Checks if a given block is a valid spot for a Persona to stand on using BlockClassification to
     * handle Partial Blocks vs Air.
     */
    public static boolean isValidStandingSpot(BlockGetter level, BlockPos pos) {
        BlockClassification feetType = BlockClassification.classify(level, pos);
        // Check Feet
        if (feetType == BlockClassification.SOLID || feetType == BlockClassification.SUPPORTING || feetType == BlockClassification.FLUID) {
            return false; // Cannot stand inside solid/supporting/fluid blocks
        }
        // Check Ground (Support)
        if (feetType == BlockClassification.TRAVERSABLE) {
            // If standing IN a Traversable block (Carpet), it provides its own support.
            // We do NOT check the block below.
        } else {
            // If standing in OPEN/PHANTOM (Air/Grass), we need support below.
            BlockPos below = pos.below();
            BlockClassification groundType = BlockClassification.classify(level, below);
            // Support must be SOLID, SUPPORTING, or TRAVERSABLE (e.g. standing on a carpet)
            if (groundType == BlockClassification.OPEN || groundType == BlockClassification.PHANTOM
                    || groundType == BlockClassification.FLUID) {
                // Special Case: "Deep Support" for vegetation?
                // No, isValidStandingSpot is local; resolveGround handles deep scans.
                return false;
            }
            // Prevent "Floating Grid" above Traversable blocks
            // If the ground is TRAVERSABLE (Carpet), the valid node is the Carpet itself, not the Air above it.
            if (groundType == BlockClassification.TRAVERSABLE) {
                return false;
            }
        }
        // Check Head
        if (!isClear(level, pos.above())) {
            return false;
        }
        // Dynamic Headroom Check (The "Concussion" Fix)
        double floorHeight = getMaxCollisionHeight(level, pos);
        if (floorHeight > HEADROOM_CHECK_THRESHOLD) {
            if (!isClear(level, pos.above(2))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a given block is a valid spot for a Persona to swim in.
     * Requires: Feet in Fluid, Head in Fluid/Air/Phantom.
     */
    public static boolean isValidSwimmingSpot(BlockGetter level, BlockPos pos) {
        if (BlockClassification.classify(level, pos) != BlockClassification.FLUID) {
            return false;
        }
        // Head must be passable for swimming (Fluid, Air, or Phantom)
        return isPassableForSwim(level, pos.above());
    }

    /**
     * Checks if a block is passable (Legacy wrapper).
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
                    // Use Classification for obstruction check
                    // Obstruction if not Traversable/Open/Phantom OR Head is blocked
                    BlockClassification type = BlockClassification.classify(level, checkPos);
                    if ((type == BlockClassification.SOLID || type == BlockClassification.SUPPORTING || type == BlockClassification.FLUID)
                            || !isClear(level, checkPos.above())) {
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
     * Checks if a block allows swimming (Water, Air, or Phantom).
     * Used for 3D water pathfinding.
     */
    public static boolean isPassableForSwim(BlockGetter level, BlockPos pos) {
        BlockClassification type = BlockClassification.classify(level, pos);
        return type == BlockClassification.FLUID ||
                type == BlockClassification.OPEN ||
                type == BlockClassification.PHANTOM;
    }

    /**
     * Checks if a block is valid for Wading (Walking physics in water).
     * Requires: Feet in Fluid, Head Clear, Ground Solid/Supporting.
     */
    public static boolean isWadable(BlockGetter level, BlockPos pos) {
        // Feet must be in fluid
        if (BlockClassification.classify(level, pos) != BlockClassification.FLUID) {
            return false;
        }
        // Head must be clear (Air/Phantom)
        if (!isClear(level, pos.above())) {
            return false;
        }
        // Ground must be solid/supporting (standard walking support)
        BlockPos below = pos.below();
        BlockClassification groundType = BlockClassification.classify(level, below);
        return groundType == BlockClassification.SOLID ||
                groundType == BlockClassification.SUPPORTING ||
                groundType == BlockClassification.TRAVERSABLE;
    }

    /**
     * Checks diagonal clearance for swimming to prevent corner clipping.
     * Simplified 2D check at the target Y level is usually sufficient for hull collision.
     */
    public static boolean isDiagonalSwimClear(BlockGetter level, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        // Cardinal moves are always clear if endpoints are clear
        if (dx == 0 || dz == 0) {
            return true;
        }
        // Check horizontal flanks
        BlockPos c1 = from.offset(dx, 0, 0);
        BlockPos c2 = from.offset(0, 0, dz);
        // Both flanks must be passable to avoid clipping
        return isPassableForSwim(level, c1) && isPassableForSwim(level, c2);
    }

    /**
     * Performs a robust "Tiered Resolution" to find the valid start node.
     * Solves "Center-Point Bias" by sweeping the entity's AABB.
     *
     * @param level
     *            The world accessor.
     * @param location
     *            The entity's exact location.
     * @param width
     *            Entity width.
     * @param height
     *            Entity height.
     * @param params
     *            Navigation parameters (swim/fall).
     * @return The resolved BlockPos, or null if no valid ground found.
     */
    public static BlockPos resolveStartNode(BlockGetter level, Location location, float width, float height, NavigationParameters params) {
        BlockPos feetPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        // Tier 1: Immediate Checks (Swimmer / Carpet)
        BlockClassification feetType = BlockClassification.classify(level, feetPos);
        if (feetType == BlockClassification.TRAVERSABLE)
            return feetPos;
        if (feetType == BlockClassification.FLUID && params.canSwim())
            return feetPos;
        // Tier 2: AABB Sweep (Ground Adjacency)
        // Create AABB at feet
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        double hw = width / 2.0;
        // Project down to find support (check blocks intersecting the area immediately below feet)
        AABB supportBox = new AABB(x - hw, y - 0.5, z - hw, x + hw, y, z + hw);
        BlockPos bestNode = null;
        double minDistSq = Double.MAX_VALUE;
        // Iterate blocks in supportBox
        int minX = Mth.floor(supportBox.minX);
        int maxX = Mth.floor(supportBox.maxX);
        int minY = Mth.floor(supportBox.minY);
        int maxY = Mth.floor(supportBox.maxY);
        int minZ = Mth.floor(supportBox.minZ);
        int maxZ = Mth.floor(supportBox.maxZ);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockPos candidate = new BlockPos(bx, by, bz);
                    BlockClassification type = BlockClassification.classify(level, candidate);
                    BlockPos node = null;
                    // If we hit solid ground, the node is ABOVE it.
                    if (type == BlockClassification.SOLID || type == BlockClassification.SUPPORTING) {
                        node = candidate.above();
                    }
                    // If we hit a carpet/slab, the node IS the carpet.
                    else if (type == BlockClassification.TRAVERSABLE) {
                        node = candidate;
                    }
                    // If we hit fluid and can swim, the node is the fluid.
                    else if (type == BlockClassification.FLUID && params.canSwim()) {
                        node = candidate;
                    }
                    // Explicitly handle PHANTOM/OPEN blocks (e.g. Tall Grass)
                    // We treat them as valid nodes ONLY if they are valid standing spots (have support below).
                    else if (type == BlockClassification.PHANTOM || type == BlockClassification.OPEN) {
                        if (isValidStandingSpot(level, candidate)) {
                            node = candidate;
                        }
                    }
                    if (node != null) {
                        // Bypass standing check for fluids
                        boolean isValid;
                        if (type == BlockClassification.FLUID) {
                            isValid = true;
                        } else {
                            isValid = isValidStandingSpot(level, node);
                        }
                        if (isValid) {
                            double distSq = node.distToCenterSqr(x, y, z);
                            if (distSq < minDistSq) {
                                minDistSq = distSq;
                                bestNode = node;
                            }
                        }
                    }
                }
            }
        }
        if (bestNode != null)
            return bestNode;
        // Tier 3: Gravity Scan (Deep Scan)
        // Fallback to the original resolveGround logic which scans straight down from feet
        // Updated to pass params for fluid support
        BlockPos gravityNode = resolveGround(level, feetPos, params.maxFallDistance(), params);
        if (gravityNode != null) {
            // Check standing OR swimming
            if (isValidStandingSpot(level, gravityNode)) {
                return gravityNode;
            }
            if (params.canSwim() && isValidSwimmingSpot(level, gravityNode)) {
                return gravityNode;
            }
        }
        // Tier 4: Fallback
        // If physics fails, trust the integer grid if it looks vaguely valid
        if (isValidStandingSpot(level, feetPos)) {
            return feetPos;
        }
        return null;
    }

    /**
     * Performs a "Deep Scan" to resolve the actual ground node from a nominal position.
     * Replaces the old physics raytrace with a classification-based logic.
     * 
     * @param level
     *            The world accessor.
     * @param nominalStart
     *            The starting block position (usually entity feet).
     * @param maxDrop
     *            Maximum blocks to scan downwards.
     * @param params
     *            Navigation parameters (to check for swimming ability).
     * @return The resolved BlockPos of the node, or null if no valid ground found.
     */
    public static BlockPos resolveGround(BlockGetter level, BlockPos nominalStart, int maxDrop, NavigationParameters params) {
        // Start scanning from the Head (y+1) to handle being inside Double-Tall Grass
        BlockPos.MutableBlockPos cursor = nominalStart.mutable().move(Direction.UP);
        for (int i = 0; i < maxDrop + 2; i++) { // +2 covers Head and Feet
            // Scan down
            BlockClassification type = BlockClassification.classify(level, cursor);
            switch (type) {
                case SOLID:
                case SUPPORTING:
                    // We hit the floor. The node is the block ABOVE.
                    return cursor.above().immutable();
                case TRAVERSABLE:
                    // We hit a Carpet/Slab. This IS the node.
                    return cursor.immutable();
                case FLUID:
                    // We hit water/lava.
                    // Fix: If we hit fluid and can swim, that IS the "ground" (surface).
                    if (params.canSwim()) {
                        return cursor.immutable();
                    }
                    return null; // Not valid ground for walking.
                case OPEN:
                case PHANTOM:
                    // Air or Grass. Continue falling.
                    break;
            }
            cursor.move(Direction.DOWN);
        }
        return null;
    }
}