package com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement;

import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacementTransform;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.UsageConstraints;
import com.ionsignal.minecraft.ionnerrus.terra.model.JigsawData;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.provider.NBTStructureProvider;
import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;
import com.ionsignal.minecraft.ionnerrus.terra.util.TransformUtil;
import com.ionsignal.minecraft.ionnerrus.terra.util.CoordinateConverter;
import com.ionsignal.minecraft.ionnerrus.terra.core.JigsawConnection;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.util.Rotation;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Random;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Logic for forcing placement of pieces to meet minimum count requirements.
 * This is a "last resort" mechanism that attempts to find valid connection points
 * for required pieces after normal generation completes.
 * 
 * IMPORTANT: This must maintain generation determinism by using the same Random
 * seed as the main generator.
 * 
 * ROTATION LOGIC:
 * For ALIGNED joints: Use base alignment rotation from calculateAlignment
 * For ROLLABLE joints:
 * 1. Calculate base alignment rotation (makes jigsaws face each other)
 * 2. Try adding 0°, 90°, 180°, 270° additional rotation around connection axis
 * 3. Recalculate structure position for each additional rotation
 */
public class ForcedPlacement {
	private static final Logger LOGGER = Logger.getLogger(ForcedPlacement.class.getName());
	private static final int MAX_FORCED_ATTEMPTS = 50; // Prevent infinite loops
	private static final List<Rotation> ALL_ROTATIONS = List.of(
			Rotation.NONE,
			Rotation.CW_90,
			Rotation.CW_180,
			Rotation.CCW_90);

	private final ConfigPack pack;
	private final Random random;

	public ForcedPlacement(ConfigPack pack, long seed) {
		this.pack = pack;
		// Derive a seed for forced placement to maintain determinism
		this.random = new Random(seed ^ 0x5DEECE66DL);
	}

	/**
	 * Attempts to force-place pieces to meet minimum requirements.
	 * 
	 * @param constraint
	 *            The constraint to satisfy
	 * @param currentCount
	 *            Current number of pieces placed
	 * @param existingPieces
	 *            Already placed pieces (potential connection points)
	 * @param occupiedSpace
	 *            Current collision boundaries
	 * @return List of forcibly placed pieces (may be empty if impossible)
	 */
	public List<PlacedJigsawPiece> forcePlacementsForMinimum(
			UsageConstraints constraint,
			int currentCount,
			List<PlacedJigsawPiece> existingPieces,
			Set<AABB> occupiedSpace) {
		List<PlacedJigsawPiece> forcedPieces = new ArrayList<>();
		int needed = constraint.minCount() - currentCount;
		if (needed <= 0) {
			return forcedPieces; // Already satisfied
		}
		LOGGER.info(String.format(
				"Attempting forced placement: need %d more pieces of %s from pool %s",
				needed,
				constraint.elementFile(),
				constraint.poolId()));
		// Load the structure data for the required element
		NBTStructure.StructureData structureData = NBTStructureProvider.getInstance()
				.load(pack, constraint.elementFile());
		if (structureData == null) {
			LOGGER.warning("Cannot force-place: structure file not found: " + constraint.elementFile());
			return forcedPieces;
		}
		// Gather all available connection points from existing pieces
		List<ConnectionCandidate> candidates = findAvailableConnections(
				existingPieces,
				structureData);
		if (candidates.isEmpty()) {
			LOGGER.warning("Cannot force-place: no available connection points");
			return forcedPieces;
		}
		// Shuffle for variety (deterministic shuffle based on our random)
		java.util.Collections.shuffle(candidates, random);
		// Attempt to place pieces at candidate locations
		int attempts = 0;
		for (ConnectionCandidate candidate : candidates) {
			if (forcedPieces.size() >= needed) {
				break; // Requirement satisfied
			}
			if (attempts++ >= MAX_FORCED_ATTEMPTS) {
				LOGGER.warning("Reached max forced placement attempts (" + MAX_FORCED_ATTEMPTS + ")");
				break;
			}
			PlacedJigsawPiece forced = tryForcePlacement(
					candidate,
					structureData,
					constraint,
					occupiedSpace);
			if (forced != null) {
				forcedPieces.add(forced);
				occupiedSpace.add(forced.getWorldBounds());
				LOGGER.fine("Forced placement successful at " + forced.worldPosition());
			}
		}
		int finalCount = currentCount + forcedPieces.size();
		if (finalCount < constraint.minCount()) {
			LOGGER.warning(String.format(
					"Forced placement incomplete: placed %d of %d required pieces (total: %d/%d)",
					forcedPieces.size(),
					needed,
					finalCount,
					constraint.minCount()));
		} else {
			LOGGER.info(String.format(
					"Forced placement successful: placed %d additional pieces (total: %d/%d)",
					forcedPieces.size(),
					finalCount,
					constraint.minCount()));
		}
		return forcedPieces;
	}

	/**
	 * Finds all available connection points from existing pieces that could
	 * theoretically connect to the required structure.
	 */
	private List<ConnectionCandidate> findAvailableConnections(
			List<PlacedJigsawPiece> pieces,
			NBTStructure.StructureData requiredStructure) {
		List<ConnectionCandidate> candidates = new ArrayList<>();
		// Get target names from the required structure's jigsaw blocks
		// These are the names that parent connections must match to connect
		Set<String> validTargets = requiredStructure.jigsawBlocks().stream()
				.map(jigsaw -> jigsaw.info().name())
				.collect(Collectors.toSet());
		for (PlacedJigsawPiece piece : pieces) {
			for (TransformedJigsawBlock connection : piece.connections()) {
				if (connection.isConsumed()) {
					continue; // Already used
				}
				// Check if this connection's target matches any jigsaw name in the required structure
				String connectionTarget = connection.info().target();
				boolean canConnect = validTargets.stream()
						.anyMatch(targetName -> JigsawConnection.matchesConnectionName(connectionTarget, targetName));
				if (canConnect) {
					candidates.add(new ConnectionCandidate(piece, connection));
				}
			}
		}
		return candidates;
	}

	/**
	 * Attempts to place a piece at a specific connection point.
	 * Uses the CORRECTED rotation logic:
	 * 1. Calculate base alignment (jigsaws facing each other)
	 * 2. For ALIGNED joints: use base alignment directly
	 * 3. For ROLLABLE joints: try adding each additional rotation (0°, 90°, 180°, 270°)
	 */
	private PlacedJigsawPiece tryForcePlacement(
			ConnectionCandidate candidate,
			NBTStructure.StructureData structureData,
			UsageConstraints constraint,
			Set<AABB> occupiedSpace) {
		TransformedJigsawBlock parentConnection = candidate.connection();
		// Find jigsaw blocks in the required structure that can connect to the parent
		List<JigsawData.JigsawBlock> compatibleJigsaws = structureData.jigsawBlocks().stream()
				.filter(jigsaw -> JigsawConnection.matchesConnectionName(
						parentConnection.info().target(),
						jigsaw.info().name()))
				.toList();
		if (compatibleJigsaws.isEmpty()) {
			return null; // No compatible connection points
		}
		// Try each compatible jigsaw
		for (JigsawData.JigsawBlock childJigsaw : compatibleJigsaws) {
			// Calculate base alignment first (using ORIGINAL child jigsaw)
			PlacementTransform baseTransform = TransformUtil.calculateAlignment(
					parentConnection,
					childJigsaw, // IMPORTANT: Use original, unrotated jigsaw
					structureData.size());
			// Determine which rotations to try based on joint type
			JigsawData.JointType jointType = childJigsaw.info().jointType();
			List<Rotation> rotationsToTry = jointType == JigsawData.JointType.ROLLABLE
					? ALL_ROTATIONS // Try all 4 rotations for ROLLABLE
					: List.of(Rotation.NONE); // Only base alignment for ALIGNED
			// Try each additional rotation
			for (Rotation additionalRotation : rotationsToTry) {
				// Combine base alignment rotation with additional rotation
				Rotation finalRotation = combineRotations(
						baseTransform.rotation(),
						additionalRotation);
				// Recalculate structure position accounting for additional rotation
				// When we rotate around the connection point, the structure origin moves
				Vector3Int finalPosition = calculatePositionWithAdditionalRotation(
						baseTransform.position(),
						childJigsaw.position(),
						additionalRotation,
						structureData.size(),
						baseTransform.rotation());
				PlacementTransform finalTransform = new PlacementTransform(
						finalPosition,
						finalRotation);
				// Check collision with existing pieces
				AABB childBounds = AABB.fromPiece(
						finalTransform.position(),
						structureData.size(),
						finalTransform.rotation());
				if (!collides(childBounds, occupiedSpace)) {
					// Success! Transform jigsaw blocks to world space
					List<TransformedJigsawBlock> connections = transformJigsawBlocks(
							structureData.jigsawBlocks(),
							finalTransform.position(),
							finalTransform.rotation(),
							structureData.size());
					// Calculate depth (parent depth + 1) and create placed piece
					int depth = candidate.parentPiece().depth() + 1;
					return new PlacedJigsawPiece(
							constraint.elementFile(),
							finalTransform.position(),
							finalTransform.rotation(),
							structureData,
							connections,
							depth,
							candidate.parentPiece(),
							constraint.poolId());
				}
			}
		}
		return null; // Failed to find non-colliding placement
	}

	/**
	 * Calculates the structure's world position when an additional rotation is applied around the
	 * connection point.
	 * 
	 * When we rotate a structure around its connection point, the structure's origin
	 * moves. This method calculates where the origin ends up.
	 * 
	 * ALGORITHM:
	 * 1. Start with base position (from base alignment)
	 * 2. Calculate where the jigsaw is after base rotation
	 * 3. Apply additional rotation to that jigsaw position
	 * 4. Calculate new structure origin position
	 * 
	 * @param basePosition
	 *            Position from base alignment calculation
	 * @param childJigsawLocalPos
	 *            Local position of child jigsaw in structure
	 * @param additionalRotation
	 *            Additional rotation being applied (0°, 90°, 180°, 270°)
	 * @param structureSize
	 *            Size of the child structure
	 * @param baseRotation
	 *            The base alignment rotation
	 * @return The final world position for the structure origin
	 */
	private Vector3Int calculatePositionWithAdditionalRotation(
			Vector3Int basePosition,
			Vector3Int childJigsawLocalPos,
			Rotation additionalRotation,
			Vector3Int structureSize,
			Rotation baseRotation) {
		if (additionalRotation == Rotation.NONE) {
			return basePosition; // No adjustment needed
		}
		// Step 1: Calculate where the jigsaw is after base rotation
		Vector3Int jigsawAfterBaseRotation = CoordinateConverter.rotate(
				childJigsawLocalPos,
				baseRotation,
				structureSize);
		// Step 2: Apply additional rotation to the jigsaw position
		// The additional rotation is around the connection point (world origin of jigsaw)
		Vector3Int jigsawAfterAdditionalRotation = CoordinateConverter.rotate(
				jigsawAfterBaseRotation,
				additionalRotation,
				calculateRotatedSize(structureSize, baseRotation));
		// Step 3: Calculate offset difference caused by additional rotation
		Vector3Int offsetDelta = Vector3Int.of(
				jigsawAfterAdditionalRotation.getX() - jigsawAfterBaseRotation.getX(),
				jigsawAfterAdditionalRotation.getY() - jigsawAfterBaseRotation.getY(),
				jigsawAfterAdditionalRotation.getZ() - jigsawAfterBaseRotation.getZ());
		// Step 4: Adjust base position by the offset delta
		return Vector3Int.of(
				basePosition.getX() - offsetDelta.getX(),
				basePosition.getY() - offsetDelta.getY(),
				basePosition.getZ() - offsetDelta.getZ());
	}

	/**
	 * Calculates the effective size of a structure after rotation.
	 * X and Z dimensions are swapped for 90° and 270° rotations.
	 */
	private Vector3Int calculateRotatedSize(Vector3Int size, Rotation rotation) {
		return switch (rotation) {
			case CW_90, CCW_90 -> Vector3Int.of(size.getZ(), size.getY(), size.getX());
			case CW_180, NONE -> size;
		};
	}

	/**
	 * Checks if a bounding box collides with any occupied space.
	 */
	private boolean collides(AABB bounds, Set<AABB> occupiedSpace) {
		for (AABB occupied : occupiedSpace) {
			if (bounds.intersects(occupied)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Combines two rotations by adding their rotation steps.
	 * 
	 * @param first
	 *            Base rotation
	 * @param second
	 *            Additional rotation to apply
	 * @return Combined rotation
	 */
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

	/**
	 * Converts a rotation to number of 90° clockwise steps.
	 */
	private int rotationToSteps(Rotation rotation) {
		return switch (rotation) {
			case NONE -> 0;
			case CW_90 -> 1;
			case CW_180 -> 2;
			case CCW_90 -> 3;
		};
	}

	/**
	 * Converts number of 90° clockwise steps to a rotation.
	 */
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
	 * Internal record for tracking potential connection points.
	 */
	private record ConnectionCandidate(
			PlacedJigsawPiece parentPiece,
			TransformedJigsawBlock connection) {
	}
}