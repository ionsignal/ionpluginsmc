package com.ionsignal.minecraft.iongenesis.generation.placements;

import com.dfsek.seismic.type.vector.Vector3Int;
import com.dfsek.seismic.type.Rotation;

/**
 * Represents a transformation to be applied when placing a structure piece.
 * This immutable record holds both the world position and rotation needed
 * to correctly place a structure piece in the world.
 * 
 * @param position
 *            The absolute world position where the structure's origin should be placed
 * @param rotation
 *            The rotation to apply to the structure
 */
public record PlacementTransform(Vector3Int position, Rotation rotation) {

    /**
     * Creates a PlacementTransform with validation.
     */
    public PlacementTransform {
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }
        if (rotation == null) {
            rotation = Rotation.NONE; // Default to no rotation if null
        }
    }

    /**
     * Creates a PlacementTransform with no rotation.
     * 
     * @param position
     *            The world position
     * @return A new PlacementTransform with NONE rotation
     */
    public static PlacementTransform noRotation(Vector3Int position) {
        return new PlacementTransform(position, Rotation.NONE);
    }

    /**
     * Applies an additional rotation to this transform.
     * 
     * @param additionalRotation
     *            The rotation to add
     * @return A new PlacementTransform with combined rotation
     */
    public PlacementTransform withAdditionalRotation(Rotation additionalRotation) {
        return new PlacementTransform(position, combineRotations(this.rotation, additionalRotation));
    }

    /**
     * Combines two rotations into a single rotation.
     * 
     * @param first
     *            The first rotation
     * @param second
     *            The second rotation to apply after the first
     * @return The combined rotation
     */
    private static Rotation combineRotations(Rotation first, Rotation second) {
        if (first == Rotation.NONE)
            return second;
        if (second == Rotation.NONE)
            return first;

        // Calculate total rotation in 90-degree increments
        int firstSteps = rotationToSteps(first);
        int secondSteps = rotationToSteps(second);
        int totalSteps = (firstSteps + secondSteps) % 4;

        return stepsToRotation(totalSteps);
    }

    /**
     * Converts a Rotation to number of 90-degree clockwise steps.
     */
    private static int rotationToSteps(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CW_90 -> 1;
            case CW_180 -> 2;
            case CCW_90 -> 3; // CCW_90 is equivalent to CW_270
        };
    }

    /**
     * Converts number of 90-degree clockwise steps to a Rotation.
     */
    private static Rotation stepsToRotation(int steps) {
        return switch (steps % 4) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CW_90;
            case 2 -> Rotation.CW_180;
            case 3 -> Rotation.CCW_90;
            default -> Rotation.NONE; // Should never happen due to modulo
        };
    }
}