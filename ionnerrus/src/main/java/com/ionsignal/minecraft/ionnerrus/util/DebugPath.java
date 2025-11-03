package com.ionsignal.minecraft.ionnerrus.util;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import java.util.logging.Logger;

public class DebugPath {
    /**
     * Logs a 2D grid of the blocks around a central location for multiple Y-levels.
     *
     * @param center
     *            The location to center the scan on.
     * @param radius
     *            The radius of the square area to scan (e.g., a radius of 4 will scan a 9x9 area).
     */
    public static void logAreaAround(Location center, int radius) {
        World world = center.getWorld();
        if (world == null)
            return;
        int pX = center.getBlockX();
        int pY = center.getBlockY();
        int pZ = center.getBlockZ();
        Logger logger = IonNerrus.getInstance().getLogger();
        logger.info("--- Area Scan Around at (" + pX + ", " + pY + ", " + pZ + ") ---");
        // Scan from 2 blocks above the player to 2 blocks below
        for (int y = pY + 2; y >= pY - 2; y--) {
            logger.info("--- Y-Level: " + y + " (Z increases downwards) ---");
            // Header row for X coordinates
            StringBuilder header = new StringBuilder("      "); // Padding for Z-coord
            for (int x = pX - radius; x <= pX + radius; x++) {
                header.append(String.format("%-4s", x));
            }
            logger.info(header.toString());
            for (int z = pZ - radius; z <= pZ + radius; z++) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%-5d: ", z)); // Z-coord prefix
                for (int x = pX - radius; x <= pX + radius; x++) {
                    if (x == pX && y == pY && z == pZ) {
                        sb.append("[P]"); // Player feet
                    } else if (x == pX && y == pY + 1 && z == pZ) {
                        sb.append("[H]"); // Player head
                    } else {
                        Material mat = new Location(world, x, y, z).getBlock().getType();
                        sb.append(getMaterialAbbreviation(mat)).append(" ");
                    }
                }
                logger.info(sb.toString());
            }
        }
        logger.info("--- End Area Scan ---");
    }

    /**
     * Provides a short, readable abbreviation for a given Material.
     * 
     * @param material
     *            The material to abbreviate.
     * @return A 3-character string representing the material.
     */
    private static String getMaterialAbbreviation(Material material) {
        if (material.isAir())
            return " . ";
        String name = material.name();
        if (name.contains("LEAVES"))
            return "[L]";
        if (name.contains("LOG"))
            return "[W]";
        if (name.contains("WATER"))
            return "~W~";
        if (name.contains("SAND"))
            return "SND";
        if (name.contains("STONE"))
            return "STN";
        if (name.contains("GRASS_BLOCK"))
            return "GRS";
        if (name.contains("DIRT"))
            return "DRT";
        if (name.contains("GLASS"))
            return "GLS";
        if (name.contains("VINE"))
            return "-V-";
        if (name.contains("WHEAT"))
            return " w ";
        if (name.contains("ITEM_FRAME"))
            return " I ";
        return name.substring(0, Math.min(name.length(), 3));
    }
}