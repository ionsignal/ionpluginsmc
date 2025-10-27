package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.util.Rotation;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.*;
import com.ionsignal.minecraft.ionnerrus.terra.model.JigsawData;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.provider.NBTStructureProvider;
import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;
import com.ionsignal.minecraft.ionnerrus.terra.util.TransformUtil;
import com.ionsignal.minecraft.ionnerrus.terra.util.CoordinateConverter;
import com.ionsignal.minecraft.ionnerrus.terra.core.JigsawConnection;

import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.PoolUsageTracker;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.GenerationStatistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * Main class for the recursive jigsaw structure generation algorithm.
 * Generates a complete JigsawPlacement from a starting pool and configuration.
 */
public class JigsawGenerator {
	private static final Logger LOGGER = Logger.getLogger(JigsawGenerator.class.getName());

	private final ConfigPack pack;
	private final Random random;
	private final JigsawStructureTemplate config;
	private final Vector3Int origin;
	private final PoolRegistry poolRegistry;

	@SuppressWarnings("unused")
	private final Platform platform;

	private final List<PlacedJigsawPiece> pieces;
	private final PriorityQueue<PendingJigsawConnection> connectionQueue;
	private final Set<AABB> occupiedSpace;

	private final PoolUsageTracker usageTracker;
	private int attemptedConnections;
	private int successfulConnections;
	private long generationStartTime;

	public JigsawGenerator(
			ConfigPack pack,
			Platform platform,
			JigsawStructureTemplate config,
			Vector3Int origin,
			long seed) {
		this.pack = pack;
		this.platform = platform;
		this.config = config;
		this.origin = origin;
		this.random = new Random(seed);
		this.poolRegistry = new PoolRegistry(pack);
		this.pieces = new ArrayList<>();
		this.connectionQueue = new PriorityQueue<>();
		this.occupiedSpace = new HashSet<>();
		this.usageTracker = new PoolUsageTracker();
		this.attemptedConnections = 0;
		this.successfulConnections = 0;
	}

	/**
	 * Generates a complete jigsaw structure placement.
	 * 
	 * @param startPoolId
	 *            The ID of the starting pool
	 * @return A JigsawPlacement containing all placed pieces
	 */
	public JigsawPlacement generate(String startPoolId) {
		// Record start time
		generationStartTime = System.currentTimeMillis();
		// Select and place initial piece from start pool
		PlacedJigsawPiece startPiece = selectAndPlaceStartPiece(startPoolId);
		if (startPiece == null) {
			// Log warning when no start piece can be placed
			LOGGER.warning("Failed to place start piece from pool: " + startPoolId);
			return JigsawPlacement.empty(origin, config.getID());
		}
		pieces.add(startPiece);
		occupiedSpace.add(startPiece.getWorldBounds());
		// Record start piece placement
		usageTracker.recordPlacement(startPiece.sourcePoolId(), startPiece.nbtFile());
		queueConnections(startPiece, 0);
		// 2. Process connection queue recursively
		while (!connectionQueue.isEmpty()) {
			PendingJigsawConnection pending = connectionQueue.poll();
			// Check depth limit
			if (pending.exceedsDepth(config.getMaxDepth())) {
				continue;
			}
			// Check distance limit
			if (pending.exceedsDistance(origin, config.getMaxDistance())) {
				continue;
			}
			// Skip if this is a terminator connection
			if (pending.isTerminator()) {
				continue;
			}
			// Try to place a connecting piece
			PlacedJigsawPiece childPiece = tryPlaceConnectingPiece(pending);
			if (childPiece != null) {
				pieces.add(childPiece);
				occupiedSpace.add(childPiece.getWorldBounds());
				queueConnections(childPiece, pending.depth() + 1);
				// Mark the connection as consumed
				updateConnectionAsConsumed(pending);
			}
		}
		// Ensure minimum pool requirements are met
		ensureMinimumPieceCounts();
		// Log generation summary
		long generationTime = System.currentTimeMillis() - generationStartTime;
		LOGGER.fine(String.format(
				"Generation completed in %dms: %d pieces placed, %d/%d connections successful",
				generationTime,
				pieces.size(),
				successfulConnections,
				attemptedConnections));
		return new JigsawPlacement(pieces, origin, config.getID());
	}

	/**
	 * Selects and places the starting piece.
	 */
	private PlacedJigsawPiece selectAndPlaceStartPiece(String startPoolId) {
		JigsawPool startPool = poolRegistry.getPool(startPoolId);
		if (startPool == null) {
			LOGGER.severe("Start pool not found: " + startPoolId);
			return null;
		}
		String nbtFile = startPool.selectRandomElement(random);
		if (nbtFile == null) {
			LOGGER.warning("Start pool is empty: " + startPoolId);
			return null;
		}
		NBTStructure.StructureData structureData = NBTStructureProvider.getInstance().load(pack, nbtFile);
		if (structureData == null) {
			LOGGER.severe("Failed to load structure file: " + nbtFile);
			return null;
		}
		// Start piece is placed at origin with random rotation
		Rotation rotation = selectRandomRotation();
		// Transform jigsaw blocks to world space
		List<TransformedJigsawBlock> connections = transformJigsawBlocks(
				structureData.jigsawBlocks(),
				origin,
				rotation,
				structureData.size());
		// Include sourcePoolId in PlacedJigsawPiece creation
		return PlacedJigsawPiece.createStartPiece(nbtFile, origin, rotation, structureData, connections, startPoolId);
	}

	/**
	 * Connection and rotation logic
	 */
	private PlacedJigsawPiece tryPlaceConnectingPiece(PendingJigsawConnection pending) {
		attemptedConnections++;
		// Get target pool
		String targetPoolId = pending.getTargetPoolId();
		JigsawPool pool = poolRegistry.getPool(targetPoolId);
		if (pool == null) {
			LOGGER.warning("Target pool not found: " + targetPoolId);
			return null;
		}
		// Increased candidates and added shuffling for variety
		List<String> candidateFiles = selectCandidateFiles(pool, 10);
		Collections.shuffle(candidateFiles, random);
		// Exhaustive search through all candidates, jigsaws, and valid rotations
		for (String nbtFile : candidateFiles) {
			NBTStructure.StructureData structureData = NBTStructureProvider.getInstance().load(pack, nbtFile);
			if (structureData == null) {
				continue;
			}
			// Shuffle for variety, so we don't always connect the same jigsaw if multiple are valid
			List<JigsawData.JigsawBlock> shuffledJigsaws = new ArrayList<>(structureData.jigsawBlocks());
			Collections.shuffle(shuffledJigsaws, random);
			for (JigsawData.JigsawBlock childJigsaw : shuffledJigsaws) {
				// A. Check if the names/targets match between the parent and potential child.
				if (!JigsawConnection.matchesConnectionName(pending.getTargetName(), childJigsaw.info().name()) ||
						!JigsawConnection.matchesConnectionName(childJigsaw.info().target(), pending.connection().info().name())) {
					continue;
				}
				// Determine rotations to try based on joint type
				List<Rotation> rotationsToTry = getRotationsForJoint(childJigsaw.info().jointType());
				// Try each valid rotation
				for (Rotation rotation : rotationsToTry) {
					// Create a rotated version of the child jigsaw for alignment calculation
					JigsawData.JigsawBlock rotatedChildJigsaw = rotateJigsawBlock(childJigsaw, rotation, structureData.size());
					// Calculate alignment with the rotated jigsaw
					PlacementTransform transform = TransformUtil.calculateAlignment(
							pending.connection(),
							rotatedChildJigsaw,
							structureData.size());
					// Apply the additional rotation to the transform
					PlacementTransform finalTransform = new PlacementTransform(
							transform.position(),
							combineRotations(transform.rotation(), rotation));
					// Check collision with existing pieces
					AABB childBounds = AABB.fromPiece(
							finalTransform.position(),
							structureData.size(),
							finalTransform.rotation());
					if (!collides(childBounds)) {
						// Transform jigsaw blocks to world space
						List<TransformedJigsawBlock> connections = transformJigsawBlocks(
								structureData.jigsawBlocks(),
								finalTransform.position(),
								finalTransform.rotation(),
								structureData.size());
						// Track successful connection
						successfulConnections++;
						// Record usage before returning
						usageTracker.recordPlacement(targetPoolId, nbtFile);
						// Include sourcePoolId in PlacedJigsawPiece creation
						return new PlacedJigsawPiece(
								nbtFile,
								finalTransform.position(),
								finalTransform.rotation(),
								structureData,
								connections,
								pending.depth() + 1,
								pending.sourcePiece(),
								targetPoolId);
					}
				}
			}
		}
		return null; // Failed to place any piece
	}

	// Get rotations to try based on joint type
	private List<Rotation> getRotationsForJoint(JigsawData.JointType jointType) {
		if (jointType == JigsawData.JointType.ROLLABLE) {
			// ROLLABLE joints can use any rotation
			return List.of(Rotation.NONE, Rotation.CW_90, Rotation.CW_180, Rotation.CCW_90);
		} else {
			// ALIGNED joints preserve orientation
			return List.of(Rotation.NONE);
		}
	}

	// Rotate a jigsaw block for alignment calculation
	private JigsawData.JigsawBlock rotateJigsawBlock(JigsawData.JigsawBlock jigsaw, Rotation rotation, Vector3Int structureSize) {
		if (rotation == Rotation.NONE) {
			return jigsaw;
		}
		// Rotate position
		Vector3Int rotatedPos = CoordinateConverter.rotate(jigsaw.position(), rotation, structureSize);
		// Rotate orientation
		String rotatedOrientation = TransformUtil.rotateOrientation(jigsaw.orientation(), rotation);
		return new JigsawData.JigsawBlock(rotatedPos, rotatedOrientation, jigsaw.info());
	}

	// Combine two rotations
	private Rotation combineRotations(Rotation first, Rotation second) {
		if (first == Rotation.NONE)
			return second;
		if (second == Rotation.NONE)
			return first;
		int firstSteps = rotationToSteps(first);
		int secondSteps = rotationToSteps(second);
		int totalSteps = (firstSteps + secondSteps) % 4;
		return stepsToRotation(totalSteps);
	}

	// Convert rotation to steps
	private int rotationToSteps(Rotation rotation) {
		return switch (rotation) {
			case NONE -> 0;
			case CW_90 -> 1;
			case CW_180 -> 2;
			case CCW_90 -> 3;
		};
	}

	// Convert steps to rotation
	private Rotation stepsToRotation(int steps) {
		return switch (steps % 4) {
			case 0 -> Rotation.NONE;
			case 1 -> Rotation.CW_90;
			case 2 -> Rotation.CW_180;
			case 3 -> Rotation.CCW_90;
			default -> Rotation.NONE;
		};
	}

	/**
	 * Queues all connections from a placed piece for processing.
	 */
	private void queueConnections(PlacedJigsawPiece piece, int depth) {
		for (TransformedJigsawBlock connection : piece.connections()) {
			if (!connection.isConsumed()) {
				PendingJigsawConnection pending = PendingJigsawConnection.create(
						connection,
						piece,
						depth);
				connectionQueue.offer(pending);
			}
		}
	}

	/**
	 * Marks a connection as consumed after successful placement.
	 */
	private void updateConnectionAsConsumed(PendingJigsawConnection pending) {
		// Find the source piece in our list and update it
		for (int i = 0; i < pieces.size(); i++) {
			if (pieces.get(i) == pending.sourcePiece()) {
				PlacedJigsawPiece updated = pieces.get(i).withConsumedConnection(
						pending.connection().position());
				pieces.set(i, updated);
				break;
			}
		}
	}

	/**
	 * Transforms jigsaw blocks from structure space to world space.
	 */
	private List<TransformedJigsawBlock> transformJigsawBlocks(
			List<JigsawData.JigsawBlock> jigsawBlocks,
			Vector3Int worldPosition,
			Rotation rotation,
			Vector3Int structureSize) {
		return jigsawBlocks.stream()
				.map(jigsaw -> TransformUtil.transformJigsawConnection(
						jigsaw,
						worldPosition,
						rotation,
						structureSize))
				.collect(Collectors.toList());
	}

	/**
	 * Checks if a bounding box collides with existing pieces.
	 */
	private boolean collides(AABB bounds) {
		for (AABB occupied : occupiedSpace) {
			if (bounds.intersects(occupied)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Selects multiple candidate files from a pool for placement attempts.
	 */
	private List<String> selectCandidateFiles(JigsawPool pool, int count) {
		List<String> candidates = new ArrayList<>();
		Set<String> selected = new HashSet<>();
		for (int i = 0; i < count; i++) {
			String file = pool.selectRandomElement(random);
			if (file != null && !selected.contains(file)) {
				candidates.add(file);
				selected.add(file);
			}
		}
		return candidates;
	}

	/**
	 * Selects a random rotation.
	 */
	private Rotation selectRandomRotation() {
		Rotation[] rotations = Rotation.values();
		return rotations[random.nextInt(rotations.length)];
	}

	/**
	 * Ensures minimum piece counts for all pools are met.
	 * This is called after the main generation pass.
	 */
	private void ensureMinimumPieceCounts() {
		// Track which pools have been used and their counts
		Map<String, Integer> poolUsage = new HashMap<>();
		// ADDED: This is a placeholder implementation
		// A full implementation would need to:
		// 1. Track which pool each piece came from (requires modifying PlacedJigsawPiece)
		// 2. Check min-count requirements from JigsawPool.WeightedElement
		// 3. Force-place additional pieces if requirements aren't met
		// For now, just log that this step would happen
		LOGGER.fine("Minimum piece count enforcement would happen here");
	}

	// PHASE 1: ADDED - Method to get generation statistics
	/**
	 * Gets comprehensive statistics about the generation process.
	 * 
	 * @return GenerationStatistics containing all metrics
	 */
	public GenerationStatistics getStatistics() {
		List<String> violations = new ArrayList<>();
		// NOTE: Violations will be populated in Phase 2 with constraint checking

		// Calculate generation time
		long generationTime = System.currentTimeMillis() - generationStartTime;

		// Aggregate pool usage from tracker
		Map<String, Integer> poolUsageSummary = usageTracker.getSnapshot().entrySet().stream()
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						e -> e.getValue().values().stream().mapToInt(Integer::intValue).sum()));

		return new GenerationStatistics(
				config.getID(),
				pieces.size(),
				pieces.stream().mapToInt(PlacedJigsawPiece::depth).max().orElse(0),
				generationTime,
				poolUsageSummary,
				violations,
				attemptedConnections,
				successfulConnections);
	}

	// PHASE 1: ADDED - Getter for usage tracker (needed for Phase 2)
	/**
	 * Gets the pool usage tracker for this generator.
	 * 
	 * @return The PoolUsageTracker instance
	 */
	public PoolUsageTracker getUsageTracker() {
		return usageTracker;
	}
}