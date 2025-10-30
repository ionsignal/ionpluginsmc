package com.ionsignal.minecraft.ionnerrus.terra.generation.debug;

import com.dfsek.terra.api.util.Rotation;
import com.dfsek.terra.api.util.vector.Vector3Int;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Holds debug state for a single jigsaw generation session.
 * Thread-safe: visualization data can be written from async thread, read from main thread.
 */
public class DebugContext {
	private static final Logger LOGGER = Logger.getLogger(DebugContext.class.getName());
	private static final long PAUSE_TIMEOUT_SECONDS = 600;

	private final UUID playerId;
	private final String structureId;
	private final Vector3Int origin;

	private final List<BlockDisplay> placedPieceDisplays = new ArrayList<>();
	private final List<BlockDisplay> transientDisplays = new ArrayList<>();
	private final List<DebugStep> stepHistory = new ArrayList<>();

	private volatile CountDownLatch stepLatch;
	private volatile boolean running = false;
	private volatile String currentPhase = "";
	private volatile String phaseInfo = "";
	private volatile boolean continueToEnd = false;
	private volatile boolean cancelled = false;

	// Visualization data (written by async thread, read by main thread)
	private volatile PlacedJigsawPiece currentPiece; // rendered via permanent layer, not transient
	private volatile NBTStructure.StructureData currentStructure;
	private volatile Vector3Int currentPosition;
	private volatile boolean visualizationDirty = false;
	private volatile boolean hasCollision = false;
	private volatile Rotation geometricRotation = Rotation.NONE;
	private volatile Rotation alignmentRotation = Rotation.NONE;
	private volatile String currentElementFile;
	private volatile String currentPoolId;
	private volatile Vector3Int activeConnectionPoint;
	private volatile World world;

	private int currentStepIndex = -1;

	/**
	 * Custom exception to cleanly abort generation from the async thread.
	 */
	public static class GenerationCancelledException extends RuntimeException {
	}

	public DebugContext(UUID playerId, String structureId, Vector3Int origin) {
		this.playerId = playerId;
		this.structureId = structureId;
		this.origin = origin;
		this.stepLatch = new CountDownLatch(1);
	}

	/**
	 * Starts the debug session.
	 */
	public void start() {
		running = true;
		currentPhase = "INITIALIZED";
		phaseInfo = "Debug session started at " + origin;
		Optional<Player> player = getPlayer();
		if (player.isPresent()) {
			this.world = player.get().getWorld();
		}
		stepLatch.countDown(); // Allow generation to start
		LOGGER.info("Debug session started for player " + playerId);
	}

	/**
	 * Pauses generation at a checkpoint.
	 */
	public void pause(String phase, String info) {
		if (cancelled) {
			throw new GenerationCancelledException();
		}
		if (!running || continueToEnd) {
			return;
		}
		currentPhase = phase;
		phaseInfo = info;
		visualizationDirty = true; // Mark for main thread to update visuals
		LOGGER.info("DEBUG PAUSE [" + phase + "]: " + info);
		stepHistory.add(new DebugStep(
				currentStepIndex + 1,
				phase,
				info,
				currentPiece,
				currentStructure,
				geometricRotation,
				alignmentRotation,
				currentPosition,
				hasCollision,
				currentElementFile,
				currentPoolId,
				activeConnectionPoint));
		currentStepIndex++;
		stepLatch = new CountDownLatch(1);
		try {
			boolean completed = stepLatch.await(PAUSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!completed) {
				LOGGER.warning("Debug pause timeout - generation may have stalled");
				stop();
			}
		} catch (InterruptedException e) {
			LOGGER.warning("Debug pause interrupted");
			stop();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Signals the generator to run to completion without further pauses.
	 */
	public void continueToEnd() {
		if (running) {
			this.continueToEnd = true;
			stepForward(); // Release the current latch to resume execution.
		}
	}

	/**
	 * Resumes from current pause.
	 */
	public void stepForward() {
		CountDownLatch current = stepLatch;
		if (current != null && current.getCount() > 0) {
			current.countDown();
		}
	}

	/**
	 * Stops debug session.
	 */
	public void stop() {
		running = false;
		cancelled = true;
		CountDownLatch current = stepLatch;
		if (current != null && current.getCount() > 0) {
			current.countDown();
		}
		LOGGER.info("Debug session stopped for player " + playerId);
	}

	/**
	 * Only clears transient visualization (test pieces, markers) and placed pieces remain visible.
	 */
	public void clearTransientVisualization() {
		for (BlockDisplay display : transientDisplays) {
			if (display != null && display.isValid()) {
				display.remove();
			}
		}
		transientDisplays.clear();
		visualizationDirty = false;
	}

	/**
	 * Clears ALL visualization entities (including placed pieces) used during session cleanup/shutdown.
	 */
	public void clearAllVisualization() {
		// Clear placed pieces
		for (BlockDisplay display : placedPieceDisplays) {
			if (display != null && display.isValid()) {
				display.remove();
			}
		}
		placedPieceDisplays.clear();
		// Clear transient displays
		clearTransientVisualization();
		LOGGER.info("Cleared all visualization displays for debug session");
	}

	/**
	 * Adds to transient displays (cleared on each update).
	 */
	public void addTransientDisplay(BlockDisplay display) {
		transientDisplays.add(display);
	}

	/**
	 * Adds to permanent placed piece displays (not cleared until session ends).
	 */
	public void addPlacedPieceDisplay(BlockDisplay display) {
		placedPieceDisplays.add(display);
	}

	/**
	 * Checks if visualization needs updating (main thread only).
	 */
	public boolean isVisualizationDirty() {
		return visualizationDirty;
	}

	/**
	 * Marks visualization as clean (main thread only).
	 */
	public void markVisualizationClean() {
		visualizationDirty = false;
	}

	// ============================================
	// Getters for readonly state
	// ============================================
	public UUID getPlayerId() {
		return playerId;
	}

	public Optional<Player> getPlayer() {
		return Optional.ofNullable(Bukkit.getPlayer(this.playerId));
	}

	public String getStructureId() {
		return structureId;
	}

	public Vector3Int getOrigin() {
		return origin;
	}

	public boolean isRunning() {
		return running;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public String getCurrentPhase() {
		return currentPhase;
	}

	public String getPhaseInfo() {
		return phaseInfo;
	}

	/**
	 * Returns count of transient displays only.
	 */
	public int getTransientDisplaysSize() {
		return this.transientDisplays.size();
	}

	/**
	 * Returns count of placed piece displays.
	 */
	public int getPlacedPieceDisplaysSize() {
		return this.placedPieceDisplays.size();
	}

	/**
	 * Gets the world reference for visualization.
	 */
	public World getWorld() {
		return world;
	}

	// ============================================
	// State setters (thread-safe, called from async)
	// ============================================

	public void setCurrentPiece(PlacedJigsawPiece piece) {
		this.currentPiece = piece;
		this.visualizationDirty = true;
	}

	public void setCurrentStructure(NBTStructure.StructureData structure) {
		this.currentStructure = structure;
		this.visualizationDirty = true;
	}

	public void setActiveConnectionPoint(Vector3Int position) {
		this.activeConnectionPoint = position;
		this.visualizationDirty = true;
	}

	public Vector3Int getActiveConnectionPoint() {
		return activeConnectionPoint;
	}

	public void setCurrentPosition(Vector3Int position) {
		this.currentPosition = position;
		this.visualizationDirty = true;
	}

	public void setHasCollision(boolean collision) {
		this.hasCollision = collision;
		this.visualizationDirty = true;
	}

	public void setGeometricRotation(Rotation rotation) {
		this.geometricRotation = rotation;
		this.visualizationDirty = true;
	}

	public void setAlignmentRotation(Rotation rotation) {
		this.alignmentRotation = rotation;
		this.visualizationDirty = true;
	}

	public void setCurrentElementFile(String file) {
		this.currentElementFile = file;
	}

	public void setCurrentPoolId(String poolId) {
		this.currentPoolId = poolId;
	}

	// ============================================
	// Getters for rendering
	// ============================================

	public PlacedJigsawPiece getCurrentPiece() {
		return currentPiece;
	}

	public NBTStructure.StructureData getCurrentStructure() {
		return currentStructure;
	}

	public Vector3Int getCurrentPosition() {
		return currentPosition;
	}

	public boolean hasCollision() {
		return hasCollision;
	}

	public Rotation getGeometricRotation() {
		return geometricRotation;
	}

	public Rotation getAlignmentRotation() {
		return alignmentRotation;
	}

	public String getCurrentElementFile() {
		return currentElementFile;
	}

	public String getCurrentPoolId() {
		return currentPoolId;
	}

	public Rotation getFinalRotation() {
		return combineRotations(this.geometricRotation, this.alignmentRotation);
	}

	private static Rotation combineRotations(Rotation first, Rotation second) {
		if (first == null || first == Rotation.NONE) {
			return second != null ? second : Rotation.NONE;
		}
		if (second == null || second == Rotation.NONE) {
			return first;
		}
		int totalSteps = (rotationToSteps(first) + rotationToSteps(second)) % 4;
		return stepsToRotation(totalSteps);
	}

	private static int rotationToSteps(Rotation rotation) {
		return switch (rotation) {
			case NONE -> 0;
			case CW_90 -> 1;
			case CW_180 -> 2;
			case CCW_90 -> 3;
		};
	}

	private static Rotation stepsToRotation(int steps) {
		return switch (steps % 4) {
			case 0 -> Rotation.NONE;
			case 1 -> Rotation.CW_90;
			case 2 -> Rotation.CW_180;
			case 3 -> Rotation.CCW_90;
			default -> Rotation.NONE;
		};
	}

	// ============================================
	// History support
	// ============================================

	public List<DebugStep> getStepHistory() {
		return new ArrayList<>(stepHistory);
	}

	public int getCurrentStepIndex() {
		return currentStepIndex;
	}

	public DebugStep getStep(int index) {
		if (index >= 0 && index < stepHistory.size()) {
			return stepHistory.get(index);
		}
		return null;
	}

	public void resetToStep(int stepIndex) {
		if (stepIndex < 0 || stepIndex >= stepHistory.size()) {
			LOGGER.warning("Cannot reset to invalid step index: " + stepIndex);
			return;
		}
		DebugStep step = stepHistory.get(stepIndex);
		this.currentPiece = step.piece();
		this.currentStructure = step.structure();
		this.geometricRotation = step.geometricRotation();
		this.alignmentRotation = step.alignmentRotation();
		this.currentPosition = step.position();
		this.hasCollision = step.hasCollision();
		this.currentElementFile = step.elementFile();
		this.currentPoolId = step.poolId();
		this.currentStepIndex = stepIndex;
		this.activeConnectionPoint = step.activeConnectionPoint();
		this.currentPhase = step.phase();
		this.phaseInfo = step.info();
		this.visualizationDirty = true;
		if (stepIndex < stepHistory.size() - 1) {
			stepHistory.subList(stepIndex + 1, stepHistory.size()).clear();
			LOGGER.info("Branch undo: truncated history to step " + stepIndex);
		}
		LOGGER.info("Reset debug context to step " + stepIndex);
	}
}