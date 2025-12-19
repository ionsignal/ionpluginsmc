package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Single source of truth for block physics semantics.
 * Eliminates ambiguity between "Physics Checks" and "Logical Checks".
 */
public enum BlockClassification {
    /** Full collision. Cannot be entered. Acts as Support (Floor). e.g., Stone, Planks. */
    SOLID,
    /** Partial collision. Cannot be entered. Acts as Support. e.g., Fence, Wall. */
    SUPPORTING,
    /** Partial collision. Can be entered. Acts as Node & Support. e.g., Carpet, Slab (<0.6m). */
    TRAVERSABLE,
    /** No collision. Can be entered. Acts as Node. e.g., Tall Grass, Flower, Open Fence Gate. */
    PHANTOM,
    /** Liquid. e.g., Water, Lava. */
    FLUID,
    /** Empty. e.g., Air. */
    OPEN;

    @SuppressWarnings("null")
    public static BlockClassification classify(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return OPEN;
        }
        // Check Phantom/Collision BEFORE Fluid.
        // A Waterlogged Fence has collision, so it is SUPPORTING/SOLID, not FLUID.
        // A Waterlogged Slab (Bottom) has collision, so it is TRAVERSABLE, not FLUID.
        // A Waterlogged Sign has NO collision, so it remains FLUID.
        // Check Phantom (Empty Collision)
        // Critical for Open Fence Gates, Vegetation, and Waterlogged non-colliding blocks (Kelp, Signs)
        if (NavigationHelper.getMaxCollisionHeight(level, pos) <= 0.0) {
            // If it has fluid, it's FLUID (swimmable). If not, it's PHANTOM (walkable/passable).
            if (!state.getFluidState().isEmpty()) {
                return FLUID;
            }
            return PHANTOM;
        }
        // Check Traversable (Step Height)
        // Handles Carpets, Snow Layers, Bottom Slabs (Waterlogged or not)
        if (NavigationHelper.getMaxCollisionHeight(level, pos) <= NavigationHelper.MAX_STEP_HEIGHT) {
            return TRAVERSABLE;
        }
        // Check Supporting (Tags)
        // Handles Fences, Walls, Closed Gates
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)) {
            return SUPPORTING;
        }
        // Default
        return SOLID;
    }
}