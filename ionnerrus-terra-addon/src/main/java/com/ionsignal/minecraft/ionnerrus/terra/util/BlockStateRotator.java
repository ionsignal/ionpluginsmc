package com.ionsignal.minecraft.ionnerrus.terra.util;

import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.block.state.properties.base.EnumProperty;
import com.dfsek.terra.api.block.state.properties.enums.Axis;
import com.dfsek.terra.api.block.state.properties.enums.Direction;
import com.dfsek.terra.api.util.Rotation;

/**
 * Utility to rotate BlockState properties.
 * This is a simplified implementation and can be expanded to handle more complex blocks like
 * stairs, slabs, and redstone.
 */
public final class BlockStateRotator {
	private static final EnumProperty<Direction> FACING = EnumProperty.of("facing", Direction.class);
	private static final EnumProperty<Axis> AXIS = EnumProperty.of("axis", Axis.class);

	private BlockStateRotator() {
		// Private constructor to prevent instantiation
	}

	/**
	 * Rotates a BlockState according to the given rotation.
	 *
	 * @param state
	 *            The original BlockState.
	 * @param rotation
	 *            The rotation to apply.
	 * @return The rotated BlockState.
	 */
	public static BlockState rotate(BlockState state, Rotation rotation) {
		if (state == null || rotation == Rotation.NONE) {
			return state;
		}

		BlockState rotatedState = state;

		// Handle 'facing' property for blocks like chests, furnaces, etc.
		if (rotatedState.has(FACING)) {
			Direction currentFacing = rotatedState.get(FACING);
			Direction newFacing = currentFacing.rotate(rotation);
			rotatedState = rotatedState.set(FACING, newFacing);
		}

		// Handle 'axis' property for blocks like logs, pillars, etc.
		if (rotatedState.has(AXIS)) {
			Axis currentAxis = rotatedState.get(AXIS);
			Axis newAxis = rotateAxis(currentAxis, rotation);
			rotatedState = rotatedState.set(AXIS, newAxis);
		}

		// Add handlers for other rotatable properties like stair shape, door hinge, etc.
		return rotatedState;
	}

	private static Axis rotateAxis(Axis axis, Rotation rotation) {
		if (rotation == Rotation.CW_180 || axis == Axis.Y) {
			return axis; // 180-degree rotation doesn't change axis, and Y-axis is invariant to horizontal rotation.
		}

		// For 90 or 270-degree rotations, X and Z axes are swapped.
		return switch (axis) {
			case X -> Axis.Z;
			case Z -> Axis.X;
			default -> axis;
		};
	}
}