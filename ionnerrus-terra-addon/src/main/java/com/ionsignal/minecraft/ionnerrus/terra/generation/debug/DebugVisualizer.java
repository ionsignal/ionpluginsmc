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

	// PHASE 4: CHANGED - Material color scheme for two-layer system
	private static final Material PLACED_PIECE_MATERIAL = Material.EMERALD_BLOCK; // Green = placed
	private static final Material TEST_CLEAR_MATERIAL = Material.GOLD_BLOCK; // Yellow = testing (clear)
	private static final Material TEST_COLLISION_MATERIAL = Material.REDSTONE_BLOCK; // Red = collision
	private static final Material VERTEX_MATERIAL = Material.GLOWSTONE; // Unchanged
	private static final Material CONNECTION_MATERIAL = Material.GOLD_BLOCK; // Unchanged

	private DebugVisualizer() {
		// Static utility class
	}

	/**
	 * Updates visuals for the current debug state this MUST be called from main thread only.
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
			// Only clear transient displays (placed pieces remain)
			context.clearTransientVisualization();
			// Show AABB and structure of the piece currently being tested
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
			LOGGER.fine(String.format("Updated visuals: %d transient, %d placed",
					context.getTransientDisplaysSize(),
					context.getPlacedPieceDisplaysSize()));
		});
	}

	/**
	 * Renders a successfully placed piece into the permanent layer.
	 * Called from async thread, scheduled to main thread.
	 * 
	 * @param world
	 *            The world to render in
	 * @param context
	 *            The debug context
	 * @param piece
	 *            The piece that was successfully placed
	 */
	public static void visualizePlacedPiece(World world, DebugContext context, PlacedJigsawPiece piece) {
		if (!Bukkit.isPrimaryThread()) {
			// Schedule to main thread if called from async
			Bukkit.getScheduler().runTask(
					Bukkit.getPluginManager().getPlugin("Terra"),
					() -> visualizePlacedPiece(world, context, piece));
			return;
		}
		if (piece == null || piece.structureData() == null) {
			return;
		}
		NBTStructure.StructureData structureData = piece.structureData();
		// Render blocks in EMERALD (green = successfully placed)
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
			// Create a block display (permanent layer)
			Location loc = new Location(world,
					worldPos.getX() + 0.5,
					worldPos.getY() + 0.5,
					worldPos.getZ() + 0.5);
			BlockDisplay display = world.spawn(loc, BlockDisplay.class);
			display.setBlock(Bukkit.createBlockData(PLACED_PIECE_MATERIAL)); // Green for placed
			display.setTransformation(new Transformation(
					new Vector3f(-0.4f, -0.4f, -0.4f),
					new org.joml.Quaternionf(),
					new Vector3f(0.8f, 0.8f, 0.8f),
					new org.joml.Quaternionf()));
			display.setGlowing(true);
			display.setViewRange(64);
			display.setBrightness(new Display.Brightness(15, 15));
			// Add to permanent placed piece layer
			context.addPlacedPieceDisplay(display);
		}
		LOGGER.fine(String.format("Visualized placed piece at %s: %d blocks (permanent layer)",
				piece.worldPosition(),
				structureData.blocks().size()));
	}

	/**
	 * Shows a placed piece's blocks using BlockDisplay entities.
	 */
	private static void showPiece(World world, DebugContext context, PlacedJigsawPiece piece, boolean isColliding) {
		if (piece == null || piece.structureData() == null) {
			return;
		}
		NBTStructure.StructureData structureData = piece.structureData();
		Material material = isColliding ? TEST_COLLISION_MATERIAL : TEST_CLEAR_MATERIAL;
		for (NBTStructure.BlockEntry block : structureData.blocks()) {
			NBTStructure.PaletteEntry paletteEntry = structureData.palette().get(block.state());
			if ("minecraft:air".equals(paletteEntry.name())) {
				continue;
			}
			Vector3Int rotatedPos = CoordinateConverter.rotate(
					block.pos(),
					piece.rotation(),
					structureData.size());
			Vector3Int worldPos = Vector3Int.of(
					piece.worldPosition().getX() + rotatedPos.getX(),
					piece.worldPosition().getY() + rotatedPos.getY(),
					piece.worldPosition().getZ() + rotatedPos.getZ());
			Location loc = new Location(world,
					worldPos.getX() + 0.5,
					worldPos.getY() + 0.5,
					worldPos.getZ() + 0.5);
			BlockDisplay display = world.spawn(loc, BlockDisplay.class);
			display.setBlock(Bukkit.createBlockData(material));
			display.setTransformation(new Transformation(
					new Vector3f(-0.4f, -0.4f, -0.4f),
					new org.joml.Quaternionf(),
					new Vector3f(0.8f, 0.8f, 0.8f),
					new org.joml.Quaternionf()));
			display.setGlowing(true);
			display.setViewRange(64);
			display.setBrightness(new Display.Brightness(15, 15));
			context.addTransientDisplay(display);
		}
		LOGGER.fine(String.format("Visualized test piece at %s: %d blocks (transient layer)",
				piece.worldPosition(),
				structureData.blocks().size()));
	}

	/**
	 * Shows the structure currently being tested (not yet placed).
	 * PHASE 4: CHANGED - Uses test materials and transient layer.
	 */
	private static void showTestStructure(World world, DebugContext context) {
		NBTStructure.StructureData structureData = context.getCurrentStructure();
		Vector3Int position = context.getCurrentPosition();
		Rotation finalRotation = context.getFinalRotation();
		boolean hasCollision = context.hasCollision();
		Material material = hasCollision ? TEST_COLLISION_MATERIAL : TEST_CLEAR_MATERIAL;
		for (NBTStructure.BlockEntry block : structureData.blocks()) {
			NBTStructure.PaletteEntry paletteEntry = structureData.palette().get(block.state());
			if ("minecraft:air".equals(paletteEntry.name())) {
				continue;
			}
			Vector3Int rotatedPos = CoordinateConverter.rotate(
					block.pos(),
					finalRotation,
					structureData.size());
			Vector3Int worldPos = Vector3Int.of(
					position.getX() + rotatedPos.getX(),
					position.getY() + rotatedPos.getY(),
					position.getZ() + rotatedPos.getZ());
			Location loc = new Location(world,
					worldPos.getX() + 0.5,
					worldPos.getY() + 0.5,
					worldPos.getZ() + 0.5);
			BlockDisplay display = world.spawn(loc, BlockDisplay.class);
			display.setBlock(Bukkit.createBlockData(material));
			display.setTransformation(new Transformation(
					new Vector3f(-0.4f, -0.4f, -0.4f),
					new org.joml.Quaternionf(),
					new Vector3f(0.8f, 0.8f, 0.8f),
					new org.joml.Quaternionf()));
			display.setGlowing(true);
			display.setViewRange(64);
			display.setBrightness(new Display.Brightness(15, 15));
			context.addTransientDisplay(display);
		}
		LOGGER.fine(String.format("Visualized test structure at %s: %d blocks",
				position,
				structureData.blocks().size()));
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
		Material material = isColliding ? TEST_COLLISION_MATERIAL : VERTEX_MATERIAL;
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
					new Vector3f(-0.15f, -0.15f, -0.15f),
					new org.joml.Quaternionf(),
					new Vector3f(0.3f, 0.3f, 0.3f),
					new org.joml.Quaternionf()));
			display.setGlowing(true);
			display.setViewRange(64);
			display.setBrightness(new Display.Brightness(15, 15));
			context.addTransientDisplay(display);
		}
		LOGGER.fine("Rendered AABB with 8 corner vertices");
	}

	/**
	 * Shows a jigsaw connection point as a marker
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
				new Vector3f(1.0f, 1.0f, 1.0f),
				new org.joml.Quaternionf()));
		display.setGlowing(true);
		display.setViewRange(64);
		display.setBrightness(new Display.Brightness(15, 15));
		context.addTransientDisplay(display);
		LOGGER.fine("Rendered connection at " + position);
	}
}