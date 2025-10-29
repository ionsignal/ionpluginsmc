package com.ionsignal.minecraft.ionnerrus.terra.generation.debug;

import com.dfsek.terra.api.util.vector.Vector3Int;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Debug command handler with proper session management and cleanup.
 */
public class DebugCommand implements CommandExecutor {
	private static final Logger LOGGER = Logger.getLogger(DebugCommand.class.getName());

	/** Links a unique generation task (structure + location) to its debug context. */
	private static final Map<DebugTaskKey, DebugContext> activeDebugTasks = new ConcurrentHashMap<>();
	/** Links a player to the key of their currently active debug task. */
	private static final Map<UUID, DebugTaskKey> playerDebugSessions = new ConcurrentHashMap<>();

	/**
	 * A simple, immutable key to uniquely identify a debug task using primitive coordinates for
	 * reliable hashing.
	 */
	private record DebugTaskKey(String structureId, int x, int y, int z) {
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
			return true;
		}

		if (args.length == 0) {
			sendHelp(player);
			return true;
		}

		String subcommand = args[0].toLowerCase();

		return switch (subcommand) {
			case "start" -> handleStart(player, args);
			case "step" -> handleStep(player, args);
			case "continue" -> handleContinue(player, args);
			case "stop" -> handleStop(player, args);
			case "status" -> handleStatus(player, args);
			case "undo" -> handleUndo(player, args);
			default -> {
				sendHelp(player);
				yield true;
			}
		};
	}

	// ============================================
	// Subcommand handlers
	// ============================================

	private boolean handleStart(Player player, String[] args) {
		if (args.length < 5) {
			player.sendMessage(Component.text("Usage: /ionnerrus:debug start <structureId> <x> <y> <z>", NamedTextColor.RED));
			return true;
		}

		UUID playerId = player.getUniqueId();
		if (playerDebugSessions.containsKey(playerId)) {
			player.sendMessage(
					Component.text("You already have an active debug session. Use '/ionnerrus:debug stop' first.", NamedTextColor.YELLOW));
			return true;
		}

		try {
			String structureId = args[1];
			int x = Integer.parseInt(args[2]);
			int y = Integer.parseInt(args[3]);
			int z = Integer.parseInt(args[4]);
			Vector3Int origin = Vector3Int.of(x, y, z);

			DebugTaskKey taskKey = new DebugTaskKey(structureId, x, y, z);

			if (activeDebugTasks.containsKey(taskKey)) {
				player.sendMessage(
						Component.text("Another player is already debugging this exact structure and location.", NamedTextColor.RED));
				return true;
			}

			DebugContext context = new DebugContext(playerId, structureId, origin);

			activeDebugTasks.put(taskKey, context);
			playerDebugSessions.put(playerId, taskKey);

			context.start();

			player.sendMessage(
					Component.text("Debug session started for structure: " + structureId + " at " + origin, NamedTextColor.GREEN));
			player.sendMessage(Component.text("Now, use Terra's command to generate the structure (e.g., /terra structure place ...)",
					NamedTextColor.GRAY));
			player.sendMessage(Component.text("Use '/ionnerrus:debug step' to advance through generation.", NamedTextColor.GRAY));

			LOGGER.info("Started debug session for player " + player.getName() + " on task: " + taskKey);
			return true;

		} catch (NumberFormatException e) {
			player.sendMessage(Component.text("Invalid coordinates. Must be integers.", NamedTextColor.RED));
			return true;
		}
	}

	private Optional<DebugContext> getContextForPlayer(Player player) {
		DebugTaskKey taskKey = playerDebugSessions.get(player.getUniqueId());
		if (taskKey == null) {
			player.sendMessage(Component.text("No active debug session. Use '/ionnerrus:debug start' first.", NamedTextColor.RED));
			return Optional.empty();
		}
		return Optional.ofNullable(activeDebugTasks.get(taskKey));
	}

	private boolean handleStep(Player player, String[] args) {
		Optional<DebugContext> contextOpt = getContextForPlayer(player);
		if (contextOpt.isEmpty() || !contextOpt.get().isRunning()) {
			return true;
		}
		DebugContext context = contextOpt.get();
		context.stepForward();
		player.sendMessage(Component.text("Stepped forward. Phase: " + context.getCurrentPhase(), NamedTextColor.GRAY));
		return true;
	}

	private boolean handleContinue(Player player, String[] args) {
		Optional<DebugContext> contextOpt = getContextForPlayer(player);
		if (contextOpt.isEmpty() || !contextOpt.get().isRunning()) {
			return true;
		}
		contextOpt.get().continueToEnd();
		player.sendMessage(Component.text("Continuing generation to completion...", NamedTextColor.GRAY));
		return true;
	}

	private boolean handleStop(Player player, String[] args) {
		clearActiveSession(player.getUniqueId());
		player.sendMessage(Component.text("Debug session stopped.", NamedTextColor.GRAY));
		return true;
	}

	private boolean handleStatus(Player player, String[] args) {
		Optional<DebugContext> contextOpt = getContextForPlayer(player);
		if (contextOpt.isEmpty() || !contextOpt.get().isRunning()) {
			return true;
		}
		DebugContext context = contextOpt.get();
		player.sendMessage(Component.text("=== Debug Session Status ===", NamedTextColor.BLUE));
		player.sendMessage(Component.text("Structure: " + context.getStructureId(), NamedTextColor.WHITE));
		player.sendMessage(Component.text("Origin: " + context.getOrigin(), NamedTextColor.WHITE));
		player.sendMessage(Component.text("Current Phase: " + context.getCurrentPhase(), NamedTextColor.YELLOW));
		player.sendMessage(Component.text("Phase Info: " + context.getPhaseInfo(), NamedTextColor.GRAY));
		player.sendMessage(Component.text("Step: " + (context.getCurrentStepIndex() + 1), NamedTextColor.GRAY));
		player.sendMessage(Component.text("Geometric Rotation: " + context.getGeometricRotation(), NamedTextColor.AQUA));
		player.sendMessage(Component.text("Alignment Rotation: " + context.getAlignmentRotation(), NamedTextColor.AQUA));
		return true;
	}

	private boolean handleUndo(Player player, String[] args) {
		Optional<DebugContext> contextOpt = getContextForPlayer(player);
		if (contextOpt.isEmpty() || !contextOpt.get().isRunning()) {
			return true;
		}
		DebugContext context = contextOpt.get();

		int currentStep = context.getCurrentStepIndex();
		if (currentStep <= 0) {
			player.sendMessage(Component.text("Cannot undo: already at start.", NamedTextColor.YELLOW));
			return true;
		}
		context.resetToStep(currentStep - 1);
		DebugVisualizer.updateVisualization(context);
		player.sendMessage(Component.text("Undone to step " + currentStep, NamedTextColor.YELLOW));
		return true;
	}

	private void sendHelp(Player player) {
		player.sendMessage(Component.text("=== Jigsaw Debug Commands ===", NamedTextColor.BLUE));
		player.sendMessage(Component.text("/ionnerrus:debug start <id> <x> <y> <z> - Start debug session", NamedTextColor.GRAY));
		player.sendMessage(Component.text("/ionnerrus:debug step - Step through one pause point", NamedTextColor.GRAY));
		player.sendMessage(Component.text("/ionnerrus:debug continue - Resume until next pause", NamedTextColor.GRAY));
		player.sendMessage(Component.text("/ionnerrus:debug status - Show current session info", NamedTextColor.GRAY));
		player.sendMessage(Component.text("/ionnerrus:debug undo - Go back one step", NamedTextColor.GRAY));
		player.sendMessage(Component.text("/ionnerrus:debug stop - Stop debug session", NamedTextColor.GRAY));
	}

	// ============================================
	// Public API for external access
	// ============================================

	/**
	 * Gets the active debug context for a specific generation task.
	 */
	public static DebugContext getDebugContextForTask(String structureId, Vector3Int origin) {
		return activeDebugTasks.get(new DebugTaskKey(structureId, origin.getX(), origin.getY(), origin.getZ()));
	}

	/**
	 * Gets the live reference to active debug tasks map (for render task).
	 * Returns the actual ConcurrentHashMap, not a copy, to allow real-time updates.
	 */
	public static Map<DebugTaskKey, DebugContext> getActiveDebugTasksReference() {
		return activeDebugTasks;
	}

	/**
	 * Checks if a player has an active debug session.
	 */
	public static boolean hasActiveSession(UUID playerId) {
		return playerDebugSessions.containsKey(playerId);
	}

	/**
	 * Clears a player's debug session with proper cleanup.
	 */
	public static void clearActiveSession(UUID playerId) {
		DebugTaskKey taskKey = playerDebugSessions.remove(playerId);
		if (taskKey != null) {
			DebugContext context = activeDebugTasks.remove(taskKey);
			if (context != null) {
				context.stop();
				context.clearVisualization(); // Clean up BlockDisplay entities
				LOGGER.info("Cleared active debug session for player " + playerId);
			}
		}
	}

	/**
	 * Clears all active sessions. Used during plugin shutdown.
	 */
	public static void clearAllSessions() {
		Map<UUID, DebugTaskKey> snapshot = new HashMap<>(playerDebugSessions);
		for (UUID playerId : snapshot.keySet()) {
			clearActiveSession(playerId);
		}
		LOGGER.info("Cleared all debug sessions during shutdown");
	}
}