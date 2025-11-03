package com.ionsignal.minecraft.ionnerrus.util;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.mojang.math.Transformation;

import net.kyori.adventure.text.format.NamedTextColor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Display.BlockDisplay;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import org.joml.Matrix4f;

public class DebugVisualizer {

    /**
     * Highlights a single block for a duration using a BlockDisplay entity.
     * This is a highly performant, client-side-only visual that does not involve
     * complex entities or scoreboard teams.
     *
     * @param blockLocation
     *            The location of the block to highlight.
     * @param durationTicks
     *            The duration in server ticks (20 ticks = 1 second).
     * @param color
     *            The NamedTextColor to use for the glow effect.
     */
    public static void highlightBlock(Location blockLocation, int durationTicks, NamedTextColor color) {
        ServerLevel level = ((CraftWorld) blockLocation.getWorld()).getHandle();
        BlockDisplay display = new BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        // Position it exactly at the block's corner.
        display.setBlockState(Blocks.WHITE_STAINED_GLASS.defaultBlockState());
        // Here, we slightly shrink the display block to prevent z-fighting with the actual block.
        float scale = 1.01f;
        float offset = (1.0f - scale) / 2.0f; // Center the smaller block.
        Matrix4f matrix = new Matrix4f().translation(offset, offset, offset).scale(scale);
        display.setTransformation(new Transformation(matrix));
        // Enable the glow effect (inherited from the base `Entity` class).
        display.setSharedFlag(6, true);
        display.setGlowColorOverride(color.value());
        display.setPos(blockLocation.getX(), blockLocation.getY(), blockLocation.getZ());
        // Spawn the entity, sending packets to nearby clients.
        level.addFreshEntity(display);
        // Schedule the removal of the entity. `discard()` is the proper NMS method for removing entities,
        Bukkit.getScheduler().runTaskLater(IonNerrus.getInstance(), () -> {
            display.discard();
        }, durationTicks);
    }

    /**
     * Displays the key waypoints of a path using BlockDisplay entities.
     * Highlights the start and end points with distinct colors.
     *
     * @param path
     *            The path to visualize.
     * @param durationTicks
     *            The duration for the visualization to last.
     */
    public static void displayPath(Path path, int durationTicks) {
        if (path == null || path.isEmpty()) {
            return;
        }
        List<Location> waypoints = path.waypoints();
        if (waypoints.size() < 2) {
            if (!waypoints.isEmpty()) {
                highlightBlock(waypoints.get(0).getBlock().getLocation(), durationTicks, NamedTextColor.AQUA);
            }
            return;
        }
        for (int i = 1; i < waypoints.size() - 1; i++) {
            highlightBlock(waypoints.get(i).getBlock().getLocation(), durationTicks, NamedTextColor.BLUE);
        }
        highlightBlock(waypoints.get(0).getBlock().getLocation(), durationTicks, NamedTextColor.GREEN);
        highlightBlock(waypoints.get(waypoints.size() - 1).getBlock().getLocation(), durationTicks, NamedTextColor.RED);
    }

    public static void visualizeBoundingBox(Location corner1, Location corner2, int durationTicks, NamedTextColor color) {
        World world = corner1.getWorld();
        if (world == null)
            return;
        double minX = Math.min(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());
        // Define the X and Z coordinates for the four vertical edges (pillars).
        double[] xCoords = { minX, maxX, maxX, minX };
        double[] zCoords = { minZ, minZ, maxZ, maxZ };
        // Loop through each of the four vertical pillars.
        for (int i = 0; i < 4; i++) {
            double currentX = xCoords[i];
            double currentZ = zCoords[i];

            // Render a column of highlighted blocks from minY to maxY for the current pillar.
            for (double y = minY; y <= maxY; y++) {
                Location blockLocation = new Location(world, currentX, y, currentZ);
                // Snap to the block grid for highlighting.
                highlightBlock(blockLocation.getBlock().getLocation(), durationTicks, color);
            }
        }
    }

}