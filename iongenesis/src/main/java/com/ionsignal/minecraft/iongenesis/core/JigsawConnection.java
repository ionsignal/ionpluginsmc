package com.ionsignal.minecraft.iongenesis.core;

import com.ionsignal.minecraft.iongenesis.model.JigsawData;

/**
 * Utility class for validating jigsaw connections between structure pieces.
 */
public final class JigsawConnection {

    private JigsawConnection() {
        // Private constructor to prevent instantiation
    }

    /**
     * Checks if two jigsaw blocks can connect to each other based on name/target and joint
     * compatibility.
     * 
     * Orientation is NOT checked here; it is handled by the rotation and alignment logic in the
     * generator.
     * 
     * @param source
     *            The source jigsaw block trying to connect
     * @param target
     *            The target jigsaw block to connect to
     * @return true if the blocks can connect, false otherwise
     */
    public static boolean canConnect(JigsawData.JigsawBlock source, JigsawData.JigsawBlock target) {
        // Check if source's target matches target's name
        if (!matchesConnectionName(source.info().target(), target.info().name())) {
            return false;
        }
        // If the target piece is a regular piece (not a terminator), enforce bidirectional connection.
        if (!"minecraft:empty".equals(target.info().target())) {
            if (!matchesConnectionName(target.info().target(), source.info().name())) {
                return false;
            }
        }
        // Joint types must be compatible
        return areJointTypesCompatible(source.info().jointType(), target.info().jointType());
    }

    /**
     * Checks if a connection target name matches a jigsaw name.
     * Supports exact matching and wildcard patterns.
     * 
     * @param target
     *            The target pattern (e.g., "village:street_connector")
     * @param name
     *            The jigsaw name to match (e.g., "village:street_connector_01")
     * @return true if they match
     */
    public static boolean matchesConnectionName(String target, String name) {
        if (target.equals(name)) {
            return true;
        }
        // Handle "minecraft:empty" special case
        if ("minecraft:empty".equals(target) || "minecraft:empty".equals(name)) {
            return false;
        }
        // Support wildcard matching (e.g., "village:street_*" matches "village:street_01")
        if (target.contains("*")) {
            String pattern = target.replace("*", ".*");
            return name.matches(pattern);
        }
        // Support category matching (without specific variant)
        // e.g., "village:street" matches "village:street_01"
        if (name.startsWith(target + "_")) {
            return true;
        }
        return false;
    }

    /**
     * Checks if two orientations are compatible for connection.
     * 
     * @param sourceOrientation
     *            The orientation of the source jigsaw
     * @param targetOrientation
     *            The orientation of the target jigsaw
     * @return true if orientations allow connection
     */
    public static boolean areOrientationsCompatible(String sourceOrientation, String targetOrientation) {
        // Opposite faces connect
        return getOppositeOrientation(sourceOrientation).equals(targetOrientation);
    }

    /**
     * Gets the opposite orientation for a given direction.
     */
    private static String getOppositeOrientation(String orientation) {
        // Split on first underscore to separate primary direction from secondary
        String lower = orientation.toLowerCase();
        String[] parts = lower.split("_", 2);
        String primary = parts[0];
        String secondary = parts.length > 1 ? "_" + parts[1] : "";
        // Get opposite of primary direction
        String oppositePrimary = switch (primary) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            case "up" -> "down";
            case "down" -> "up";
            default -> primary; // Unknown direction, return as-is
        };
        // Reconstruct with secondary component preserved
        // Examples:
        // "north_up" -> "south_up"
        // "north" -> "south"
        // "up" -> "down"
        return oppositePrimary + secondary;
    }

    /**
     * Checks if two joint types are compatible.
     * 
     * @param sourceJoint
     *            Joint type of the source
     * @param targetJoint
     *            Joint type of the target
     * @return true if joints are compatible
     */
    public static boolean areJointTypesCompatible(JigsawData.JointType sourceJoint, JigsawData.JointType targetJoint) {
        // Both must be the same type, or one must be rollable
        if (sourceJoint == JigsawData.JointType.ROLLABLE ||
                targetJoint == JigsawData.JointType.ROLLABLE) {
            return true;
        }
        return sourceJoint == targetJoint;
    }

    /**
     * Calculates the priority for processing a connection.
     * Higher values mean higher priority.
     * 
     * @param jigsaw
     *            The jigsaw block to get priority for
     * @return The processing priority
     */
    public static int getConnectionPriority(JigsawData.JigsawBlock jigsaw) {
        int priority = jigsaw.info().placementPriority();
        // Adjust priority based on orientation (vertical connections often have higher priority)
        if ("up".equals(jigsaw.orientation()) || "down".equals(jigsaw.orientation())) {
            priority += 10;
        }
        return priority;
    }
}