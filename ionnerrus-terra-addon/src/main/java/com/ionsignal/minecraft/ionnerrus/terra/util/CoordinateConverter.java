package com.ionsignal.minecraft.ionnerrus.terra.util;

import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.util.Rotation;
import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;

// import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure.PaletteEntry;

import java.util.stream.Collectors;

/**
 * Utility class for handling conversions and calculations needed for structure generation,
 * such as rotation and block state parsing.
 */
public final class CoordinateConverter {

	private CoordinateConverter() {
		// Private constructor to prevent instantiation
	}

	/**
	 * Parses a PaletteEntry into a Terra BlockState.
	 * Constructs a string representation (e.g., "minecraft:chest[facing=north]")
	 * and uses the Terra Platform API to create the BlockState object.
	 *
	 * @param entry
	 *            The palette entry from the parsed NBT structure.
	 * @param platform
	 *            The Terra Platform instance, used for its world handle.
	 * @return The corresponding Terra BlockState, or null on failure.
	 */
	public static BlockState parsePaletteEntry(PaletteEntry entry, Platform platform) {
		try {
			StringBuilder sb = new StringBuilder(entry.name());
			if (entry.properties() != null && !entry.properties().isEmpty()) {
				String propertiesString = entry.properties().entrySet().stream()
						.map(e -> e.getKey() + "=" + e.getValue())
						.collect(Collectors.joining(",", "[", "]"));
				sb.append(propertiesString);
			}
			return platform.getWorldHandle().createBlockState(sb.toString());
		} catch (Exception e) {
			// IonNerrus.getInstance().getLogger().warning("Failed to parse block state: " + entry.name());
			return null; // Return null to indicate failure, allowing the caller to skip this block.
		}
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
	 * Converts a Terra API Rotation enum to a Seismic library Rotation enum.
	 *
	 * @param terraRotation
	 *            The rotation object provided by the Terra API.
	 * @return The equivalent Seismic Rotation object.
	 */
	public static Rotation toSeismicRotation(com.dfsek.terra.api.util.Rotation terraRotation) {
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