package com.ionsignal.minecraft.iongenesis.util;

import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;

/**
 * Pure mathematical utilities for spatial operations, vector rotation, and coordinate system
 * conversions.
 */
public final class SpatialMath {

    private SpatialMath() {
        // Private constructor to prevent instantiation
    }

    /**
     * Rotates a relative position vector within a structure's bounds.
     *
     * @param pos
     *            The original relative position of the block.
     * @param rotation
     *            The rotation to apply (from Terra's API).
     * @param size
     *            The size of the structure's bounding box.
     * @return A new Vector3Int with the rotated coordinates.
     */
    public static Vector3Int rotate(Vector3Int pos, Rotation rotation, Vector3Int size) {
        return switch (rotation) {
            case CW_90 -> Vector3Int.of(size.getZ() - 1 - pos.getZ(), pos.getY(), pos.getX());
            case CW_180 -> Vector3Int.of(size.getX() - 1 - pos.getX(), pos.getY(), size.getZ() - 1 - pos.getZ());
            case CCW_90 -> Vector3Int.of(pos.getZ(), pos.getY(), size.getX() - 1 - pos.getX());
            default -> pos; // Case for NONE rotation
        };
    }

    /**
     * Rotates the dimensions (size) of a structure.
     *
     * @param size
     *            The original size.
     * @param rotation
     *            The rotation to apply.
     * @return The new size vector (swapping X and Z if rotation is 90/270 degrees).
     */
    public static Vector3Int rotateDimensions(Vector3Int size, Rotation rotation) {
        if (rotation == Rotation.CW_90 || rotation == Rotation.CCW_90) {
            return Vector3Int.of(size.getZ(), size.getY(), size.getX());
        }
        return size;
    }

    /**
     * Converts a Terra API Rotation enum to a Seismic library Rotation enum.
     *
     * @param terraRotation
     *            The rotation object provided by the Terra API.
     * @return The equivalent Seismic Rotation object.
     */
    public static Rotation toSeismicRotation(com.dfsek.seismic.type.Rotation terraRotation) {
        if (terraRotation == null) {
            return Rotation.NONE;
        }
        return switch (terraRotation) {
            case CW_90 -> Rotation.CW_90;
            case CW_180 -> Rotation.CW_180;
            case CCW_90 -> Rotation.CCW_90;
            case NONE -> Rotation.NONE;
        };
    }
}