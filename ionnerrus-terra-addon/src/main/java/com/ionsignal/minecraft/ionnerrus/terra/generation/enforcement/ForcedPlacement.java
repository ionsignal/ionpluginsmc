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
import java.util.Collections;
import java.util.Set;
import java.util.Random;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ForcedPlacement {
	private static final Logger LOGGER = Logger.getLogger(ForcedPlacement.class.getName());
	private static final int MAX_FORCED_ATTEMPTS = 50;
	private static final List<Rotation> ALL_ROTATIONS = List.of(
			Rotation.NONE,
			Rotation.CW_90,
			Rotation.CW_180,
			Rotation.CCW_90);

	private final ConfigPack pack;
	private final Random random;

	public ForcedPlacement(ConfigPack pack, long seed) {
		this.pack = pack;
		this.random = new Random(seed ^ 0x5DEECE66DL);
	}

	/**
	 * Attempts to force-place pieces to meet minimum requirements. Returns a result object containing
	 * both placed pieces and consumed connections
	 * 
	 * @return ForcedPlacementResult with pieces and connections to mark as consumed
	 */
	public ForcedPlacementResult forcePlacementsForMinimum(
			UsageConstraints constraint,
			int currentCount,
			List<PlacedJigsawPiece> existingPieces,
			Set<AABB> occupiedSpace) {
		List<PlacedJigsawPiece> forcedPieces = new ArrayList<>();
		List<ConnectionUsage> consumedConnections = new ArrayList<>();
		int needed = constraint.minCount() - currentCount;
		if (needed <= 0) {
			return new ForcedPlacementResult(forcedPieces, consumedConnections);
		}
		LOGGER.info(String.format(
				"Attempting forced placement: need %d more pieces of %s from pool %s",
				needed,
				constraint.elementFile(),
				constraint.poolId()));
		NBTStructure.StructureData structureData = NBTStructureProvider.getInstance()
				.load(pack, constraint.elementFile());
		if (structureData == null) {
			LOGGER.warning("Cannot force-place: structure file not found: " + constraint.elementFile());
			return new ForcedPlacementResult(forcedPieces, consumedConnections);
		}
		List<ConnectionCandidate> candidates = findAvailableConnections(
				existingPieces,
				structureData);
		if (candidates.isEmpty()) {
			LOGGER.warning("Cannot force-place: no available connection points");
			return new ForcedPlacementResult(forcedPieces, consumedConnections);
		}
		Collections.shuffle(candidates, random);
		int attempts = 0;
		for (ConnectionCandidate candidate : candidates) {
			if (forcedPieces.size() >= needed) {
				break;
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
				consumedConnections.add(new ConnectionUsage(
						candidate.parentPiece(),
						candidate.connection().position()));
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
		return new ForcedPlacementResult(forcedPieces, consumedConnections);
	}

	/**
	 * Finds all available connection points from existing pieces using JigsawConnection.canConnect()
	 * for proper bidirectional validation
	 */
	private List<ConnectionCandidate> findAvailableConnections(
			List<PlacedJigsawPiece> pieces,
			NBTStructure.StructureData requiredStructure) {
		List<ConnectionCandidate> candidates = new ArrayList<>();
		for (PlacedJigsawPiece piece : pieces) {
			for (TransformedJigsawBlock connection : piece.connections()) {
				if (connection.isConsumed()) {
					continue;
				}
				// Convert TransformedJigsawBlock to JigsawBlock for validation
				JigsawData.JigsawBlock parentJigsaw = connection.toJigsawBlock();
				// Check against ALL jigsaws in the required structure
				for (JigsawData.JigsawBlock childJigsaw : requiredStructure.jigsawBlocks()) {
					// Use bidirectional validation
					if (JigsawConnection.canConnect(parentJigsaw, childJigsaw)) {
						candidates.add(new ConnectionCandidate(piece, connection));
						break; // Only need to know ONE jigsaw can connect
					}
				}
			}
		}
		return candidates;
	}

	/**
	 * Attempts to place a piece at a specific connection point.
	 * 
	 * 1. For ALIGNED joints: Calculate alignment directly (no additional rotation)
	 * 2. For ROLLABLE joints: PRE-ROTATE child jigsaw, THEN calculate alignment
	 */
	private PlacedJigsawPiece tryForcePlacement(
			ConnectionCandidate candidate,
			NBTStructure.StructureData structureData,
			UsageConstraints constraint,
			Set<AABB> occupiedSpace) {
		TransformedJigsawBlock parentConnection = candidate.connection();
		JigsawData.JigsawBlock parentJigsaw = parentConnection.toJigsawBlock();
		List<JigsawData.JigsawBlock> compatibleJigsaws = structureData.jigsawBlocks().stream()
				.filter(childJigsaw -> JigsawConnection.canConnect(parentJigsaw, childJigsaw))
				.toList();
		if (compatibleJigsaws.isEmpty()) {
			return null;
		}
		for (JigsawData.JigsawBlock childJigsaw : compatibleJigsaws) {
			JigsawData.JointType jointType = childJigsaw.info().jointType();
			if (jointType == JigsawData.JointType.ROLLABLE) {
				// ROLLABLE: Try all 4 rotations
				for (Rotation geometricRotation : ALL_ROTATIONS) {
					// Step 1: Pre-rotate the child jigsaw
					JigsawData.JigsawBlock rotatedChildJigsaw = rotateJigsawBlock(
							childJigsaw,
							geometricRotation,
							structureData.size());
					// Step 2: Calculate alignment with the pre-rotated jigsaw
					PlacementTransform alignmentTransform = TransformUtil.calculateAlignment(
							parentConnection,
							rotatedChildJigsaw,
							structureData.size());
					// Step 3: Combine rotations properly
					Rotation finalRotation = combineRotations(geometricRotation, alignmentTransform.rotation());
					// Step 4: Check collision using GEOMETRIC rotation for bounds
					AABB childBounds = AABB.fromPiece(
							alignmentTransform.position(),
							structureData.size(),
							geometricRotation // ← Use geometric rotation for collision bounds
					);
					if (collides(childBounds, occupiedSpace)) {
						continue;
					}
					// Step 5: Transform connections using FINAL rotation
					List<TransformedJigsawBlock> connections = transformJigsawBlocks(
							structureData.jigsawBlocks(),
							alignmentTransform.position(),
							finalRotation, // ← Use combined rotation
							structureData.size());
					// Step 6: Create placed piece with FINAL rotation
					int depth = candidate.parentPiece().depth() + 1;
					return new PlacedJigsawPiece(
							constraint.elementFile(),
							alignmentTransform.position(),
							finalRotation, // ← Store final rotation
							structureData,
							connections,
							depth,
							candidate.parentPiece(),
							constraint.poolId());
				}
			} else {
				// ALIGNED: No pre-rotation, use alignment directly
				PlacementTransform alignmentTransform = TransformUtil.calculateAlignment(
						parentConnection,
						childJigsaw,
						structureData.size());
				// For ALIGNED joints, the alignment rotation IS the final rotation
				Rotation finalRotation = alignmentTransform.rotation();
				AABB childBounds = AABB.fromPiece(
						alignmentTransform.position(),
						structureData.size(),
						Rotation.NONE // ← ALIGNED joints have no geometric rotation
				);
				if (!collides(childBounds, occupiedSpace)) {
					List<TransformedJigsawBlock> connections = transformJigsawBlocks(
							structureData.jigsawBlocks(),
							alignmentTransform.position(),
							finalRotation,
							structureData.size());

					int depth = candidate.parentPiece().depth() + 1;
					return new PlacedJigsawPiece(
							constraint.elementFile(),
							alignmentTransform.position(),
							finalRotation,
							structureData,
							connections,
							depth,
							candidate.parentPiece(),
							constraint.poolId());
				}
			}
		}

		return null;
	}

	private static Rotation combineRotations(Rotation first, Rotation second) {
		if (first == Rotation.NONE)
			return second;
		if (second == Rotation.NONE)
			return first;
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

	private boolean collides(AABB bounds, Set<AABB> occupiedSpace) {
		for (AABB occupied : occupiedSpace) {
			if (bounds.intersects(occupied)) {
				return true;
			}
		}
		return false;
	}

	private JigsawData.JigsawBlock rotateJigsawBlock(
			JigsawData.JigsawBlock jigsaw,
			Rotation rotation,
			Vector3Int structureSize) {
		if (rotation == Rotation.NONE) {
			return jigsaw;
		}
		Vector3Int rotatedPos = CoordinateConverter.rotate(
				jigsaw.position(),
				rotation,
				structureSize);
		String rotatedOrientation = TransformUtil.rotateOrientation(
				jigsaw.orientation(),
				rotation);
		return new JigsawData.JigsawBlock(rotatedPos, rotatedOrientation, jigsaw.info());
	}

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
	 * Result of forced placement operation.
	 * Contains both the placed pieces and which connections were consumed.
	 */
	public record ForcedPlacementResult(
			List<PlacedJigsawPiece> pieces,
			List<ConnectionUsage> consumedConnections) {
	}

	/**
	 * Tracks which connection on which parent piece was consumed.
	 */
	public record ConnectionUsage(
			PlacedJigsawPiece parentPiece,
			Vector3Int connectionPosition) {
	}

	/**
	 * Possible connection candidate
	 */
	private record ConnectionCandidate(
			PlacedJigsawPiece parentPiece,
			TransformedJigsawBlock connection) {
	}
}