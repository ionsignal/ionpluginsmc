package com.ionsignal.minecraft.ionnerrus.terra.generation.debug;

import com.dfsek.terra.api.util.Rotation;
import com.dfsek.terra.api.util.vector.Vector3Int;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

// import java.util.logging.Logger;

/**
 * PHASE 3: NEW - Formats debug information for display to players.
 * Shows current generation state in actionbar/chat.
 */
public final class DebugInfoFormatter {
	// private static final Logger LOGGER = Logger.getLogger(DebugInfoFormatter.class.getName());

	private DebugInfoFormatter() {
		// Static utility class
	}

	/**
	 * PHASE 3: ADDED - Formats actionbar message with current state.
	 * Shows: Step count, phase, rotations, and collision status.
	 */
	public static Component formatActionbar(DebugContext context) {
		if (context == null || !context.isRunning()) {
			return Component.text("No active debug session");
		}
		String phase = context.getCurrentPhase();
		Rotation geoRot = context.getGeometricRotation();
		Rotation alignRot = context.getAlignmentRotation();
		boolean collision = context.hasCollision();
		String file = context.getCurrentElementFile() != null ? context.getCurrentElementFile() : "?";
		// PHASE 3: ADDED - Build actionbar with current state
		return Component.text()
				.append(Component.text("[", NamedTextColor.GRAY))
				.append(Component.text(String.format("Step %d", context.getCurrentStepIndex() + 1), NamedTextColor.YELLOW))
				.append(Component.text("] ", NamedTextColor.GRAY))
				.append(Component.text(phase, NamedTextColor.BLUE))
				.append(Component.text(" | ", NamedTextColor.DARK_GRAY))
				.append(Component.text("Geo: ", NamedTextColor.GRAY))
				.append(Component.text(getRotationShort(geoRot), NamedTextColor.AQUA))
				.append(Component.text(" | Align: ", NamedTextColor.GRAY))
				.append(Component.text(getRotationShort(alignRot), NamedTextColor.AQUA))
				.append(Component.text(" | ", NamedTextColor.DARK_GRAY))
				.append(Component.text(collision ? "⚠ COLLISION" : "✓ Clear",
						collision ? NamedTextColor.RED : NamedTextColor.GREEN))
				.build();
	}

	/**
	 * PHASE 3: ADDED - Formats detailed debug info for chat display.
	 */
	public static Component formatDetailedInfo(DebugContext context) {
		if (context == null || !context.isRunning()) {
			return Component.text("No active debug session", NamedTextColor.RED);
		}
		Vector3Int position = context.getCurrentPosition();
		Rotation geoRot = context.getGeometricRotation();
		Rotation alignRot = context.getAlignmentRotation();
		String file = context.getCurrentElementFile() != null ? context.getCurrentElementFile() : "unknown";
		String pool = context.getCurrentPoolId() != null ? context.getCurrentPoolId() : "unknown";
		// PHASE 3: ADDED - Build detailed info
		return Component.text()
				.append(Component.text("=== Debug Step " + (context.getCurrentStepIndex() + 1) + " ===\n", NamedTextColor.BLUE))
				.append(Component.text("Phase: ", NamedTextColor.GRAY))
				.append(Component.text(context.getCurrentPhase(), NamedTextColor.YELLOW))
				.append(Component.text("\nInfo: ", NamedTextColor.GRAY))
				.append(Component.text(context.getPhaseInfo(), NamedTextColor.WHITE))
				.append(Component.text("\nFile: ", NamedTextColor.GRAY))
				.append(Component.text(file, NamedTextColor.WHITE))
				.append(Component.text("\nPool: ", NamedTextColor.GRAY))
				.append(Component.text(pool, NamedTextColor.WHITE))
				.append(Component.text("\nGeometric Rotation: ", NamedTextColor.GRAY))
				.append(Component.text(getRotationName(geoRot), NamedTextColor.AQUA))
				.append(Component.text("\nAlignment Rotation: ", NamedTextColor.GRAY))
				.append(Component.text(getRotationName(alignRot), NamedTextColor.AQUA))
				.append(Component.text("\nPosition: ", NamedTextColor.GRAY))
				.append(Component.text(position != null ? position.toString() : "N/A", NamedTextColor.WHITE))
				.append(Component.text("\nCollision: ", NamedTextColor.GRAY))
				.append(Component.text(context.hasCollision() ? "YES" : "NO",
						context.hasCollision() ? NamedTextColor.RED : NamedTextColor.GREEN))
				.build();
	}

	/**
	 * PHASE 3: ADDED - Gets full rotation name for display.
	 */
	private static String getRotationName(Rotation rotation) {
		return switch (rotation) {
			case NONE -> "NONE (0°)";
			case CW_90 -> "CW_90 (90°)";
			case CW_180 -> "CW_180 (180°)";
			case CCW_90 -> "CCW_90 (270°)";
		};
	}

	/**
	 * PHASE 3: ADDED - Gets short rotation name for actionbar (saves space).
	 */
	private static String getRotationShort(Rotation rotation) {
		return switch (rotation) {
			case NONE -> "0°";
			case CW_90 -> "90°";
			case CW_180 -> "180°";
			case CCW_90 -> "270°";
		};
	}
}