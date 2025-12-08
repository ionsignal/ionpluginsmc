package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.bukkit.Location;

/**
 * Encapsulates all necessary data to define a pathfinding operation.
 * Handles internal conversion between Bukkit Locations, NMS BlockPos, and Physics Bounds.
 */
public record PathfindingRequest(
        Location start,
        Location end,
        float entityWidth,
        float entityHeight,
        NavigationParameters params) {
    public BlockPos startBlock() {
        return new BlockPos(start.getBlockX(), start.getBlockY(), start.getBlockZ());
    }

    public BlockPos endBlock() {
        return new BlockPos(end.getBlockX(), end.getBlockY(), end.getBlockZ());
    }

    /**
     * Calculates the Axis Aligned Bounding Box (AABB) for the entity at the exact start location.
     */
    public AABB getStartBounds() {
        double x = start.getX();
        double y = start.getY();
        double z = start.getZ();
        double hw = entityWidth / 2.0;
        return new AABB(x - hw, y, z - hw, x + hw, y + entityHeight, z + hw);
    }
}