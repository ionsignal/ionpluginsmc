package com.ionsignal.minecraft.ionnerrus.terra.util;

import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.util.Rotation;

import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacementTransform;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.ionnerrus.terra.model.JigsawData;

/**
 * Utility class for spatial transformations required for jigsaw structure generation.
 * This class contains the core mathematical functions for aligning and rotating
 * structure pieces to connect properly at their jigsaw points.
 */
public final class TransformUtil {

	private TransformUtil() {
		// Private constructor to prevent instantiation
	}

	/**
	 * Transforms a jigsaw connection from structure-local space to world space.
	 * This accounts for the structure's world position and rotation.
	 * 
	 * @param jigsawBlock
	 *            The jigsaw block in structure-local coordinates
	 * @param structureWorldPos
	 *            The world position of the structure's origin
	 * @param structureRotation
	 *            The rotation applied to the structure
	 * @param structureSize
	 *            The size of the structure (needed for rotation calculations)
	 * @return A TransformedJigsawBlock with world coordinates and rotated orientation
	 */
	public static TransformedJigsawBlock transformJigsawConnection(
			JigsawData.JigsawBlock jigsawBlock,
			Vector3Int structureWorldPos,
			Rotation structureRotation,
			Vector3Int structureSize) {
		// Step 1: Rotate the jigsaw's position within the structure bounds
		Vector3Int rotatedLocalPos = CoordinateConverter.rotate(
				jigsawBlock.position(),
				structureRotation,
				structureSize);
		// Step 2: Calculate world position
		Vector3Int worldPos = Vector3Int.of(
				structureWorldPos.getX() + rotatedLocalPos.getX(),
				structureWorldPos.getY() + rotatedLocalPos.getY(),
				structureWorldPos.getZ() + rotatedLocalPos.getZ());
		// Step 3: Rotate the jigsaw's orientation
		String rotatedOrientation = rotateOrientation(jigsawBlock.orientation(), structureRotation);
		// Create the transformed jigsaw block
		return new TransformedJigsawBlock(
				worldPos,
				rotatedOrientation,
				jigsawBlock.info());
	}

	/**
	 * Calculates the placement transform needed to align a child structure with a parent connection
	 * point.
	 * This is the core algorithm for connecting jigsaw pieces together.
	 * 
	 * @param parentConnection
	 *            The connection point on the already-placed parent structure (in world space)
	 * @param childJigsaw
	 *            The jigsaw block in the child structure to connect (in structure-local space)
	 * @param childStructureSize
	 *            The size of the child structure
	 * @return A PlacementTransform containing the world position and rotation for the child structure
	 */
	public static PlacementTransform calculateAlignment(
			TransformedJigsawBlock parentConnection,
			JigsawData.JigsawBlock childJigsaw,
			Vector3Int childStructureSize) {
		// Step 1: Determine the rotation needed to align the child's jigsaw with the parent's
		Rotation requiredRotation = calculateRequiredRotation(
				parentConnection.orientation(),
				childJigsaw.orientation());
		// Step 2: Rotate the child jigsaw's position within its structure
		Vector3Int rotatedChildJigsawPos = CoordinateConverter.rotate(
				childJigsaw.position(),
				requiredRotation,
				childStructureSize);
		// Step 3: Calculate the offset needed to align the connection points
		// The child's rotated jigsaw position should align with the parent's connection point
		// We need to account for the face-to-face connection (jigsaws touch at their faces)
		Vector3Int connectionOffset = getConnectionOffset(parentConnection.orientation());
		// Step 4: Calculate the child structure's world position
		// childWorldPos + rotatedChildJigsawPos = parentConnection.position() + connectionOffset
		Vector3Int childWorldPos = Vector3Int.of(
				parentConnection.position().getX() + connectionOffset.getX() - rotatedChildJigsawPos.getX(),
				parentConnection.position().getY() + connectionOffset.getY() - rotatedChildJigsawPos.getY(),
				parentConnection.position().getZ() + connectionOffset.getZ() - rotatedChildJigsawPos.getZ());
		return new PlacementTransform(childWorldPos, requiredRotation);
	}

	/**
	 * Rotates a jigsaw orientation string by the given rotation.
	 * Handles both simple orientations (e.g., "north") and compound orientations (e.g., "north_up").
	 * 
	 * @param orientation
	 *            The original orientation string
	 * @param rotation
	 *            The rotation to apply
	 * @return The rotated orientation string
	 */
	public static String rotateOrientation(String orientation, Rotation rotation) {
		if (rotation == Rotation.NONE) {
			return orientation;
		}
		// Parse compound orientations (e.g., "north_up")
		String[] parts = orientation.toLowerCase().split("_");
		String primaryDir = parts[0];
		// Default secondary to north if not present, crucial for consistent rotation logic.
		String secondaryDir = parts.length > 1 ? parts[1] : "north";
		// If primary is vertical, we rotate the secondary component.
		if ("up".equals(primaryDir) || "down".equals(primaryDir)) {
			String rotatedSecondary = rotateHorizontalDirection(secondaryDir, rotation);
			return primaryDir + "_" + rotatedSecondary;
		}
		// If primary is horizontal, we rotate the primary and keep the secondary.
		// The secondary part (like _up) is preserved relative to the piece, so it doesn't rotate.
		// Example: "north_up" rotated 90 degrees becomes "east_up".
		String rotatedPrimary = rotateHorizontalDirection(primaryDir, rotation);
		return parts.length > 1 ? rotatedPrimary + "_" + parts[1] : rotatedPrimary;
	}

	/**
	 * Rotates a horizontal direction by the given rotation.
	 * 
	 * @param direction
	 *            The direction to rotate (north, south, east, west)
	 * @param rotation
	 *            The rotation to apply
	 * @return The rotated direction
	 */
	private static String rotateHorizontalDirection(String direction, Rotation rotation) {
		// Handle vertical directions (no rotation needed)
		if ("up".equals(direction) || "down".equals(direction)) {
			return direction;
		}

		// Convert direction to index (north=0, east=1, south=2, west=3)
		int dirIndex = switch (direction) {
			case "north" -> 0;
			case "east" -> 1;
			case "south" -> 2;
			case "west" -> 3;
			default -> -1;
		};

		if (dirIndex == -1) {
			return direction; // Unknown direction, return as-is
		}

		// Apply rotation
		int rotationSteps = switch (rotation) {
			case CW_90 -> 1;
			case CW_180 -> 2;
			case CCW_90 -> 3;
			case NONE -> 0;
		};

		int newIndex = (dirIndex + rotationSteps) % 4;

		// Convert back to direction string
		return switch (newIndex) {
			case 0 -> "north";
			case 1 -> "east";
			case 2 -> "south";
			case 3 -> "west";
			default -> direction; // Should never happen
		};
	}

	/**
	 * Calculates the rotation needed to align two jigsaw orientations.
	 * The child's orientation must be rotated to face opposite to the parent's.
	 * 
	 * @param parentOrientation
	 *            The orientation of the parent jigsaw (in world space)
	 * @param childOrientation
	 *            The orientation of the child jigsaw (in structure space)
	 * @return The rotation to apply to the child structure
	 */
	private static Rotation calculateRequiredRotation(String parentOrientation, String childOrientation) {
		// Parse orientations into primary and secondary components, defaulting to "north" if unspecified.
		String[] parentParts = parentOrientation.toLowerCase().split("_");
		String parentPrimary = parentParts[0];
		String parentSecondary = parentParts.length > 1 ? parentParts[1] : "north";
		String[] childParts = childOrientation.toLowerCase().split("_");
		String childPrimary = childParts[0];
		String childSecondary = childParts.length > 1 ? childParts[1] : "north";
		// If the connection is vertical (e.g., up <-> down), align the secondary orientations.
		if ("up".equals(parentPrimary) || "down".equals(parentPrimary)) {
			// The target for the child's secondary orientation is the parent's secondary orientation.
			// This ensures an "up_north" aligns with a "down_north", not a "down_east".
			return calculateRotationBetweenDirections(childSecondary, parentSecondary);
		}
		// If the connection is horizontal, the logic remains the same.
		// The target for the child's primary orientation is the opposite of the parent's primary.
		String targetPrimary = getOppositeDirection(parentPrimary);
		return calculateRotationBetweenDirections(childPrimary, targetPrimary);
	}

	/**
	 * Gets the opposite direction for a given direction.
	 * 
	 * @param direction
	 *            The original direction
	 * @return The opposite direction
	 */
	private static String getOppositeDirection(String direction) {
		return switch (direction.toLowerCase()) {
			case "north" -> "south";
			case "south" -> "north";
			case "east" -> "west";
			case "west" -> "east";
			case "up" -> "down";
			case "down" -> "up";
			default -> direction;
		};
	}

	/**
	 * Calculates the rotation needed to turn from one horizontal direction to another.
	 * 
	 * @param from
	 *            The starting direction
	 * @param to
	 *            The target direction
	 * @return The rotation needed
	 */
	private static Rotation calculateRotationBetweenDirections(String from, String to) {
		if (from.equals(to)) {
			return Rotation.NONE;
		}

		// Convert directions to indices
		int fromIndex = directionToIndex(from);
		int toIndex = directionToIndex(to);

		if (fromIndex == -1 || toIndex == -1) {
			return Rotation.NONE; // Unknown direction
		}

		// Calculate the rotation steps needed (positive = clockwise)
		int steps = (toIndex - fromIndex + 4) % 4;

		return switch (steps) {
			case 0 -> Rotation.NONE;
			case 1 -> Rotation.CW_90;
			case 2 -> Rotation.CW_180;
			case 3 -> Rotation.CCW_90;
			default -> Rotation.NONE;
		};
	}

	/**
	 * Converts a direction string to an index (north=0, east=1, south=2, west=3).
	 * 
	 * @param direction
	 *            The direction string
	 * @return The index, or -1 if unknown
	 */
	private static int directionToIndex(String direction) {
		return switch (direction.toLowerCase()) {
			case "north" -> 0;
			case "east" -> 1;
			case "south" -> 2;
			case "west" -> 3;
			default -> -1;
		};
	}

	/**
	 * Gets the offset to apply when connecting two jigsaw blocks.
	 * This accounts for the fact that jigsaws connect face-to-face.
	 * 
	 * @param parentOrientation
	 *            The orientation of the parent jigsaw
	 * @return The offset vector to apply for the connection
	 */
	private static Vector3Int getConnectionOffset(String parentOrientation) {
		String primaryDir = parentOrientation.toLowerCase().split("_")[0];

		// The offset moves one block in the direction the parent jigsaw is facing
		// This places the child's jigsaw face-to-face with the parent's
		return switch (primaryDir) {
			case "north" -> Vector3Int.of(0, 0, -1);
			case "south" -> Vector3Int.of(0, 0, 1);
			case "east" -> Vector3Int.of(1, 0, 0);
			case "west" -> Vector3Int.of(-1, 0, 0);
			case "up" -> Vector3Int.of(0, 1, 0);
			case "down" -> Vector3Int.of(0, -1, 0);
			default -> Vector3Int.of(0, 0, 0); // Unknown orientation
		};
	}

	/**
	 * Validates if two jigsaw orientations can connect.
	 * They must face opposite directions to connect properly.
	 * 
	 * @param orientation1
	 *            First jigsaw orientation
	 * @param orientation2
	 *            Second jigsaw orientation
	 * @return true if the orientations are compatible for connection
	 */
	public static boolean areOrientationsCompatible(String orientation1, String orientation2) {
		String dir1 = orientation1.toLowerCase().split("_")[0];
		String dir2 = orientation2.toLowerCase().split("_")[0];

		return dir2.equals(getOppositeDirection(dir1));
	}
}