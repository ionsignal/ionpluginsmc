package com.ionsignal.minecraft.ionnerrus.terra.generation.debug;

import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;
import com.ionsignal.minecraft.ionnerrus.terra.util.CoordinateConverter;

import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.util.Rotation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.logging.Logger;

/**
 * Renders debug visualizations using BlockDisplay entities.
 * MUST only be called from the main server thread.
 */
public final class DebugVisualizer {
	private static final Logger LOGGER = Logger.getLogger(DebugVisualizer.class.getName());

	// Materials for visualization
	private static final Material VERTEX_MATERIAL = Material.GLOWSTONE;
	private static final Material COLLISION_MATERIAL = Material.REDSTONE_BLOCK;
	private static final Material CLEAR_MATERIAL = Material.EMERALD_BLOCK;
	private static final Material CONNECTION_MATERIAL = Material.GOLD_BLOCK;

	private DebugVisualizer() {
		// Static utility class
	}

	/**
	 * Updates visuals for the current debug state.
	 * MUST be called from main thread only.
	 */
	public static void updateVisualization(DebugContext context) {
		if (!Bukkit.isPrimaryThread()) {
			LOGGER.severe("DebugVisualizer.updateVisualization called from async thread!");
			return;
		}
		if (context == null || !context.isRunning() || !context.isVisualizationDirty()) {
			return;
		}
		context.getPlayer().ifPresent(player -> {
			World world = player.getWorld();
			// Clear old displays
			context.clearVisualization();
			// Show current piece being tested
			if (context.getCurrentPiece() != null) {
				showPiece(world, context, context.getCurrentPiece(), context.hasCollision());
			}
			// Show AABB of the piece currently being tested
			if (context.getCurrentStructure() != null && context.getCurrentPosition() != null) {
				AABB testBounds = AABB.fromPiece(
						context.getCurrentPosition(),
						context.getCurrentStructure().size(),
						context.getGeometricRotation());
				showTestStructure(world, context);
				showAABB(world, context, testBounds, context.hasCollision());
			}
			// Show the specific connection point being attempted
			if (context.getActiveConnectionPoint() != null) {
				showConnection(world, context, context.getActiveConnectionPoint());
			}
			context.markVisualizationClean();
			LOGGER.fine("Updated visuals for debug context");
		});
	}

	/**
	 * Shows a placed piece's blocks using BlockDisplay entities.
	 */
	private static void showPiece(World world, DebugContext context, PlacedJigsawPiece piece, boolean isColliding) {
		if (piece == null || piece.structureData() == null) {
			return;
		}
		NBTStructure.StructureData structureData = piece.structureData();
		// Render a subset of blocks (every 2nd block to reduce entity count)
		for (NBTStructure.BlockEntry block : structureData.blocks()) {
			NBTStructure.PaletteEntry paletteEntry = structureData.palette().get(block.state());
			if ("minecraft:air".equals(paletteEntry.name())) {
				continue;
			}
			// Get the block position in world space
			Vector3Int rotatedPos = CoordinateConverter.rotate(
					block.pos(),
					piece.rotation(),
					structureData.size());
			Vector3Int worldPos = Vector3Int.of(
					piece.worldPosition().getX() + rotatedPos.getX(),
					piece.worldPosition().getY() + rotatedPos.getY(),
					piece.worldPosition().getZ() + rotatedPos.getZ());
			// Create a small block display at this position
			Location loc = new Location(world,
					worldPos.getX() + 0.5,
					worldPos.getY() + 0.5,
					worldPos.getZ() + 0.5);
			BlockDisplay display = world.spawn(loc, BlockDisplay.class);
			display.setBlock(Bukkit.createBlockData(isColliding ? COLLISION_MATERIAL : CLEAR_MATERIAL));
			display.setTransformation(new Transformation(
					new Vector3f(-0.4f, -0.4f, -0.4f), // Translation (center the 0.8 scale block)
					new org.joml.Quaternionf(), // No rotation
					new Vector3f(0.8f, 0.8f, 0.8f), // Scale with slight gaps for clarity
					new org.joml.Quaternionf() // No right rotation
			));
			display.setGlowing(true);
			display.setViewRange(64);
			display.setBrightness(new Display.Brightness(15, 15));
			context.addDisplay(display);
		}
		int totalBlocks = structureData.blocks().size();
		int displayCount = context.getActiveDisplaysSize();
		LOGGER.fine(String.format(
				"Visualized piece at %s: %d BlockDisplays created from %d total blocks",
				piece.worldPosition(),
				displayCount,
				totalBlocks));
	}

	/**
	 * Shows the structure currently being tested (not yet placed).
	 * Uses raw structure data + position + rotation from debug context.
	 */
	private static void showTestStructure(World world, DebugContext context) {
		NBTStructure.StructureData structureData = context.getCurrentStructure();
		Vector3Int position = context.getCurrentPosition();
		Rotation finalRotation = context.getFinalRotation();
		boolean hasCollision = context.hasCollision();
		// Render all blocks for complete visualization
		for (NBTStructure.BlockEntry block : structureData.blocks()) {
			// Skip air blocks - no point rendering invisible blocks
			NBTStructure.PaletteEntry paletteEntry = structureData.palette().get(block.state());
			if ("minecraft:air".equals(paletteEntry.name())) {
				continue;
			}
			// Get the block position in world space
			Vector3Int rotatedPos = CoordinateConverter.rotate(
					block.pos(),
					finalRotation,
					structureData.size());
			Vector3Int worldPos = Vector3Int.of(
					position.getX() + rotatedPos.getX(),
					position.getY() + rotatedPos.getY(),
					position.getZ() + rotatedPos.getZ());
			// Create a block display at this position
			Location loc = new Location(world,
					worldPos.getX() + 0.5,
					worldPos.getY() + 0.5,
					worldPos.getZ() + 0.5);
			BlockDisplay display = world.spawn(loc, BlockDisplay.class);
			display.setBlock(Bukkit.createBlockData(hasCollision ? COLLISION_MATERIAL : CLEAR_MATERIAL));
			display.setTransformation(new Transformation(
					new Vector3f(-0.4f, -0.4f, -0.4f), // Translation (center the 0.8 scale block)
					new org.joml.Quaternionf(), // No rotation
					new Vector3f(0.8f, 0.8f, 0.8f), // Scale with slight gaps for clarity
					new org.joml.Quaternionf() // No right rotation
			));
			display.setGlowing(true);
			display.setViewRange(64);
			display.setBrightness(new Display.Brightness(15, 15));
			context.addDisplay(display);
		}
		LOGGER.fine(String.format(
				"Visualized test structure at %s: %d blocks, collision=%s",
				position,
				structureData.blocks().size(),
				hasCollision));
	}

	/**
	 * Shows an AABB outline using BlockDisplay entities at the 8 vertices.
	 */
	private static void showAABB(World world, DebugContext context, AABB aabb, boolean isColliding) {
		if (aabb == null) {
			return;
		}
		Vector3Int min = aabb.min();
		Vector3Int max = aabb.max();
		Material material = isColliding ? COLLISION_MATERIAL : VERTEX_MATERIAL;
		// Create displays at the 8 corners
		int[][] corners = {
				{ min.getX(), min.getY(), min.getZ() },
				{ max.getX(), min.getY(), min.getZ() },
				{ min.getX(), max.getY(), min.getZ() },
				{ min.getX(), min.getY(), max.getZ() },
				{ max.getX(), max.getY(), min.getZ() },
				{ max.getX(), min.getY(), max.getZ() },
				{ min.getX(), max.getY(), max.getZ() },
				{ max.getX(), max.getY(), max.getZ() }
		};
		for (int[] corner : corners) {
			Location loc = new Location(world, corner[0] + 0.5, corner[1] + 0.5, corner[2] + 0.5);
			BlockDisplay display = world.spawn(loc, BlockDisplay.class);
			display.setBlock(Bukkit.createBlockData(material));
			display.setTransformation(new Transformation(
					new Vector3f(-0.15f, -0.15f, -0.15f), // Center the scaled block
					new org.joml.Quaternionf(),
					new Vector3f(0.3f, 0.3f, 0.3f), // Small corner markers
					new org.joml.Quaternionf()));
			display.setGlowing(true);
			display.setViewRange(64);
			display.setBrightness(new Display.Brightness(15, 15));
			context.addDisplay(display);
		}
		LOGGER.fine("Rendered AABB with 8 corner vertices");
	}

	/**
	 * Shows a jigsaw connection point as a marker.
	 */
	public static void showConnection(World world, DebugContext context, Vector3Int position) {
		if (world == null || position == null)
			return;
		Location loc = new Location(world,
				position.getX() + 0.5,
				position.getY() + 0.5,
				position.getZ() + 0.5);
		BlockDisplay display = world.spawn(loc, BlockDisplay.class);
		display.setBlock(Bukkit.createBlockData(CONNECTION_MATERIAL));
		display.setTransformation(new Transformation(
				new Vector3f(-0.5f, -0.5f, -0.5f),
				new org.joml.Quaternionf(),
				new Vector3f(1.0f, 1.0f, 1.0f), // Larger for connection points
				new org.joml.Quaternionf()));
		display.setGlowing(true);
		display.setViewRange(64);
		display.setBrightness(new Display.Brightness(15, 15));
		context.addDisplay(display);
		LOGGER.fine("Rendered connection at " + position);
	}
}