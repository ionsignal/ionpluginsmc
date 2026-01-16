package com.ionsignal.minecraft.iongenesis.model.structure;

import com.dfsek.seismic.type.vector.Vector3Int;

/**
 * Data model for jigsaw connection points found in structure files.
 * Jigsaw blocks define how structure pieces can connect to each other.
 */
public final class JigsawData {

    private JigsawData() {
        // Private constructor to prevent instantiation
    }

    /**
     * Represents a jigsaw connection point within a structure.
     * 
     * @param name
     *            The name of this jigsaw connection (e.g., "street_connector")
     * @param target
     *            The target pool to select a connecting piece from (e.g., "village/streets")
     * @param pool
     *            The pool this jigsaw becomes after connection (fallback pool)
     * @param jointType
     *            The type of joint ("rollable" allows rotation, "aligned" preserves orientation)
     * @param placementPriority
     *            Priority for processing this connection (higher = earlier)
     * @param finalState
     *            // CHANGED: ADDED - The block state to place after connection (e.g., "minecraft:air")
     */
    public record JigsawInfo(
            String name,
            String target,
            String pool,
            JointType jointType,
            int placementPriority,
            String finalState) {
        public JigsawInfo {
            // Validate required fields
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Jigsaw name cannot be null or empty");
            }
            if (target == null || target.isEmpty()) {
                throw new IllegalArgumentException("Jigsaw target cannot be null or empty");
            }
            if (finalState == null || finalState.isEmpty()) {
                throw new IllegalArgumentException("Jigsaw finalState cannot be null or empty");
            }
        }
    }

    /**
     * Represents a jigsaw block within a structure, combining position and connection info.
     * 
     * @param position
     *            The relative position within the structure
     * @param orientation
     *            The facing direction of the jigsaw block
     * @param info
     *            The jigsaw connection information
     */
    public record JigsawBlock(
            Vector3Int position,
            String orientation,
            JigsawInfo info) {
    }

    /**
     * Types of joints for jigsaw connections.
     */
    public enum JointType {
        /**
         * Rollable joints allow the connecting piece to be rotated around the connection axis.
         */
        ROLLABLE("rollable"),

        /**
         * Aligned joints preserve the orientation of connecting pieces.
         */
        ALIGNED("aligned");

        private final String nbtValue;

        JointType(String nbtValue) {
            this.nbtValue = nbtValue;
        }

        public String getNbtValue() {
            return nbtValue;
        }

        public static JointType fromNbt(String value) {
            for (JointType type : values()) {
                if (type.nbtValue.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            // Default to aligned if unknown
            return ALIGNED;
        }
    }
}