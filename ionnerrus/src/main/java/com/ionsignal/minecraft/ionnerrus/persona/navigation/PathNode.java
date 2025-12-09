package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult.MovementType;

import net.minecraft.core.BlockPos;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Represents a single point on a Path with rich metadata about the environment to make intelligent
 * steering decisions without expensive runtime ray-tracing.
 *
 * @param pos
 *            The raw block position.
 * @param type
 *            The type of movement required to reach the *next* node (e.g., JUMP to get to i+1).
 * @param clearanceRadius
 *            The "Drivability" metric. Distance to the nearest obstacle.
 *            0.5 = Tight corridor (Rail movement).
 *            3.0+ = Open field (Smooth cornering).
 * @param apexRadius
 *            The "Geometric Constraint" metric. Determines how tight the turn must be.
 *            0.5 = Tight turn (Corner/Doorway).
 *            2.0 = Loose turn (Open Air).
 * @param surfaceOffset
 *            The height off the surface given the bounding box.
 *            (e.g., 0.5 for slab, 0.0625 for carpet)
 */
public record PathNode(
        BlockPos pos,
        MovementType type,
        double clearanceRadius,
        double apexRadius,
        double surfaceOffset) {

    /**
     * Converts the block position to a centered Bukkit Location.
     */
    public Location toLocation(World world) {
        return new Location(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}