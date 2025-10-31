package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.core.JigsawConnection;
import com.ionsignal.minecraft.ionnerrus.terra.generation.debug.DebugContext;
import com.ionsignal.minecraft.ionnerrus.terra.generation.debug.DebugVisualizer;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ConstraintViolation;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.EnforcementStrategy;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ForcedPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.JigsawPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PendingJigsawConnection;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacementTransform;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.TransformedJigsawBlock;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.GenerationStatistics;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.PoolUsageTracker;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.UsageConstraints;
import com.ionsignal.minecraft.ionnerrus.terra.model.JigsawData;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.provider.NBTStructureProvider;
import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;
import com.ionsignal.minecraft.ionnerrus.terra.util.CoordinateConverter;
import com.ionsignal.minecraft.ionnerrus.terra.util.TransformUtil;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.util.Rotation;
import com.dfsek.terra.api.util.vector.Vector3Int;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Main class for the recursive jigsaw structure generation algorithm.
 * Generates a complete JigsawPlacement from a starting pool and configuration.
 */
public class JigsawGenerator {
	private static final Logger LOGGER = Logger.getLogger(JigsawGenerator.class.getName());
	private static final boolean DEBUG_CONNECTIONS = true;

	private final ConfigPack pack;
	private final Platform platform;
	private final Random random;
	private final JigsawStructureTemplate config;
	private final Vector3Int origin;
	private final PoolRegistry poolRegistry;
	private final List<PlacedJigsawPiece> pieces;
	private final PriorityQueue<PendingJigsawConnection> connectionQueue;
	private final Set<AABB> occupiedSpace;
	private final PoolUsageTracker usageTracker;
	private final ConnectionRegistry connectionRegistry;
	private final EnforcementStrategy enforcementStrategy;
	private final List<UsageConstraints> allConstraints;
	private final ForcedPlacement forcedPlacement;

	private int attemptedConnections;
	private int successfulConnections;
	private long generationStartTime;

	private final DebugContext debugContext;

	/**
	 * Violations captured during enforcement phase.
	 */
	private final List<ConstraintViolation> capturedViolations = new ArrayList<>();

	public JigsawGenerator(
			ConfigPack pack,
			Platform platform,
			JigsawStructureTemplate config,
			Vector3Int origin,
			long seed) {
		this(pack, platform, config, origin, seed, null);
	}

	/**
	 * Constructor with optional debug context
	 */
	public JigsawGenerator(
			ConfigPack pack,
			Platform platform,
			JigsawStructureTemplate config,
			Vector3Int origin,
			long seed,
			DebugContext debugContext) {
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
		this.connectionRegistry = new ConnectionRegistry();
		this.attemptedConnections = 0;
		this.successfulConnections = 0;
		this.enforcementStrategy = EnforcementStrategy.fromConfig(config.getEnforcementStrategy());
		this.allConstraints = gatherConstraintsFromPools();
		if (enforcementStrategy != EnforcementStrategy.BEST_EFFORT && allConstraints.isEmpty()) {
			LOGGER.warning(String.format(
					"Enforcement strategy is '%s' but no constraints were found. " +
							"This may indicate a configuration issue or that pools haven't loaded yet.",
					enforcementStrategy.getConfigValue()));
		}
		this.forcedPlacement = new ForcedPlacement(pack, seed);
		this.debugContext = debugContext;
		if (!allConstraints.isEmpty()) {
			long minConstraints = allConstraints.stream().filter(UsageConstraints::hasMinimum).count();
			long maxConstraints = allConstraints.stream().filter(UsageConstraints::hasMaximum).count();
			LOGGER.info(String.format(
					"Generator initialized with enforcement strategy: %s, tracking %d constraints (%d min, %d max)",
					enforcementStrategy.getConfigValue(),
					allConstraints.size(),
					minConstraints,
					maxConstraints));
		}
	}

	/**
	 * Gathers all usage constraints from pools referenced by this structure
	 */
	private List<UsageConstraints> gatherConstraintsFromPools() {
		try {
			List<UsageConstraints> constraints = poolRegistry.getAllConstraints();
			if (constraints.isEmpty()) {
				LOGGER.fine("No constraints found in pool registry...");
				return Collections.emptyList();
			}
			List<UsageConstraints> filtered = constraints.stream()
					.filter(c -> c.hasMinimum() || c.hasMaximum())
					.toList();
			List<UsageConstraints> sorted = filtered.stream()
					.sorted((c1, c2) -> {
						int poolCompare = c1.poolId().compareTo(c2.poolId());
						if (poolCompare != 0) {
							return poolCompare;
						}
						return c1.elementFile().compareTo(c2.elementFile());
					})
					.toList();
			LOGGER.info(String.format(
					"Gathered and sorted %d constraints (deterministic order ensured)",
					sorted.size()));
			return sorted;
		} catch (Exception e) {
			LOGGER.log(Level.WARNING,
					"Failed to gather constraints from pool registry...",
					e);
			return Collections.emptyList();
		}
	}

	/**
	 * Generates a complete jigsaw structure placement.
	 */
	public JigsawPlacement generate(String startPoolId) {
		generationStartTime = System.currentTimeMillis();
		capturedViolations.clear();

		PlacedJigsawPiece startPiece = selectAndPlaceStartPiece(startPoolId);
		if (startPiece == null) {
			LOGGER.warning("Failed to place start piece from pool: " + startPoolId);
			return JigsawPlacement.empty(origin, config.getID(), connectionRegistry);
		}
		pieces.add(startPiece);
		occupiedSpace.add(startPiece.getWorldBounds());
		// usageTracker.recordPlacement(startPiece.sourcePoolId(), startPiece.nbtFile());
		queueConnections(startPiece, 0);
		while (!connectionQueue.isEmpty()) {
			if (debugContext != null && debugContext.isCancelled()) {
				throw new DebugContext.GenerationCancelledException();
			}
			PendingJigsawConnection pending = connectionQueue.poll();
			if (pending.exceedsDepth(config.getMaxDepth())) {
				continue;
			}
			if (pending.exceedsDistance(origin, config.getMaxDistance())) {
				continue;
			}
			if (pending.isTerminator()) {
				continue;
			}
			PlacedJigsawPiece childPiece = tryPlaceConnectingPiece(pending);
			if (childPiece != null) {
				pieces.add(childPiece);
				occupiedSpace.add(childPiece.getWorldBounds());
				queueConnections(childPiece, pending.depth() + 1);
				// Mark parent connection as consumed in registry
				connectionRegistry.markConsumed(pending.connection().position());
			}
		}
		ensureMinimumPieceCounts();
		long generationTime = System.currentTimeMillis() - generationStartTime;
		LOGGER.info(String.format(
				"Generation completed on platform %s in %dms: %d pieces placed, %d/%d connections successful%s",
				platform.platformName(),
				generationTime,
				pieces.size(),
				successfulConnections,
				attemptedConnections,
				capturedViolations.isEmpty() ? "" : " (" + capturedViolations.size() + " violations)"));
		return new JigsawPlacement(pieces, origin, config.getID(), connectionRegistry);
	}

	private Vector3Int calculateTransformedJigsawPosition(
			JigsawData.JigsawBlock jigsaw,
			Vector3Int worldPosition,
			Rotation rotation,
			Vector3Int structureSize) {
		Vector3Int rotatedPos = CoordinateConverter.rotate(jigsaw.position(), rotation, structureSize);
		Vector3Int worldPos = Vector3Int.of(
				worldPosition.getX() + rotatedPos.getX(),
				worldPosition.getY() + rotatedPos.getY(),
				worldPosition.getZ() + rotatedPos.getZ());
		if (DEBUG_CONNECTIONS) {
			LOGGER.info(String.format(
					"  Transform jigsaw: local=(xyz=%s,%s,%s) -> rotated=(xyz=%s,%s,%s) (rot=%s, size=(xyz=%s,%s,%s)) -> world=(xyz=%s,%s,%s) (base=(xyz=%s,%s,%s))",
					jigsaw.position().getX(),
					jigsaw.position().getY(),
					jigsaw.position().getZ(),
					rotatedPos.getX(),
					rotatedPos.getY(),
					rotatedPos.getZ(),
					rotation.getDegrees(),
					structureSize.getX(),
					structureSize.getY(),
					structureSize.getZ(),
					worldPos.getX(),
					worldPos.getY(),
					worldPos.getZ(),
					worldPosition.getX(),
					worldPosition.getY(),
					worldPosition.getZ()));
		}
		return worldPos;
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
		Rotation rotation = selectRandomRotation();
		List<TransformedJigsawBlock> connections = transformJigsawBlocks(
				structureData.jigsawBlocks(),
				origin,
				rotation,
				structureData.size());
		usageTracker.recordPlacement(startPool.getId(), nbtFile);
		PlacedJigsawPiece startPiece = PlacedJigsawPiece.createStartPiece(nbtFile,
				origin,
				rotation,
				structureData,
				connections,
				startPool.getId());
		if (DEBUG_CONNECTIONS) {
			LOGGER.info(String.format("START PIECE placed: file=%s, pos=(xyz=%s,%s,%s), rot=%s",
					nbtFile,
					origin.getX(),
					origin.getY(),
					origin.getZ(),
					rotation.getDegrees()));
			LOGGER.info("  Start piece jigsaws after placement:");
			for (TransformedJigsawBlock conn : connections) {
				LOGGER.info(String.format("    pos=(xyz=%s%s%s), orientation=%s, target=%s",
						conn.position().getX(),
						conn.position().getY(),
						conn.position().getZ(),
						conn.orientation(),
						conn.info().target()));
			}
		}
		if (this.debugContext != null && this.debugContext.isRunning()) {
			World world = this.debugContext.getWorld();
			if (world != null) {
				DebugVisualizer.visualizePlacedPiece(world, this.debugContext, startPiece);
				LOGGER.fine("Visualized start piece in permanent layer: " + nbtFile);
			}
		}
		return startPiece;
	}

	/**
	 * Attempts to place a piece at a pending connection point.
	 * 
	 * CRITICAL FIX: This method now correctly handles ROLLABLE joints by:
	 * 1. Calculating alignment rotation ONCE from the UNROTATED child orientation
	 * 2. Combining geometric rotation with alignment rotation to get final rotation
	 * 3. Using final rotation directly for all calculations (position, collision, transforms)
	 * 
	 * This ensures that all 4 geometric rotations (0°, 90°, 180°, 270°) produce different
	 * positions and orientations, providing the variety expected from ROLLABLE joints.
	 */
	private PlacedJigsawPiece tryPlaceConnectingPiece(PendingJigsawConnection pending) {
		attemptedConnections++;
		if (DEBUG_CONNECTIONS) {
			LOGGER.info(String.format("ATTEMPTING CONNECTION from parent at (xyz=%s,%s,%s):",
					pending.sourcePiece().worldPosition().getX(),
					pending.sourcePiece().worldPosition().getY(),
					pending.sourcePiece().worldPosition().getZ()));
			LOGGER.info(String.format("  Parent connection: pos=(xyz=%s,%s,%s), orientation=%s, target=%s",
					pending.connection().position().getX(),
					pending.connection().position().getY(),
					pending.connection().position().getZ(),
					pending.connection().orientation(),
					pending.getTargetName()));
		}
		String targetPoolId = pending.getTargetPoolId();
		if (this.debugContext != null && this.debugContext.isRunning()) {
			this.debugContext.setCurrentPiece(pending.sourcePiece());
			this.debugContext.setCurrentPoolId(targetPoolId);
			this.debugContext.setActiveConnectionPoint(pending.connection().position());
		}
		JigsawPool pool = poolRegistry.getPool(targetPoolId);
		if (pool == null) {
			LOGGER.warning("Target pool not found: " + targetPoolId);
			return null;
		}
		List<String> candidateFiles = selectCandidateFilesRespectingMaxCounts(pool, 10);
		if (candidateFiles.isEmpty()) {
			LOGGER.fine("No eligible candidates from pool " + targetPoolId);
			return null;
		}
		Collections.shuffle(candidateFiles, random);
		for (String nbtFile : candidateFiles) {
			NBTStructure.StructureData structureData = NBTStructureProvider.getInstance().load(pack, nbtFile);
			if (structureData == null) {
				continue;
			}
			List<JigsawData.JigsawBlock> shuffledJigsaws = new ArrayList<>(structureData.jigsawBlocks());
			Collections.shuffle(shuffledJigsaws, random);
			for (JigsawData.JigsawBlock childJigsaw : shuffledJigsaws) {
				if (!JigsawConnection.canConnect(pending.connection().toJigsawBlock(), childJigsaw)) {
					continue;
				}
				// Get the list of geometric rotations to try based on joint type
				// ALIGNED joints: [NONE] only
				// ROLLABLE joints: [NONE, CW_90, CW_180, CCW_90]
				List<Rotation> rotationsToTry = getRotationsForJoint(childJigsaw.info().jointType());
				for (Rotation geometricRotation : rotationsToTry) {
					// CRITICAL FIX: Calculate alignment from ROTATED orientation
					// Step 1: Apply geometric rotation to get the rotated jigsaw state
					JigsawData.JigsawBlock rotatedChildJigsaw = rotateJigsawBlock(
							childJigsaw,
							geometricRotation,
							structureData.size());
					// Step 2: Calculate alignment based on ROTATED orientation (not original)
					// This is what additional rotation is needed after geometric rotation
					Rotation alignmentRotation = calculateRequiredRotation(
							pending.connection().orientation(),
							rotatedChildJigsaw.orientation() // Use ROTATED orientation
					);
					// Step 3: Combine rotations for the final result
					// finalRotation = geometricRotation + alignmentRotation
					Rotation finalRotation = combineRotations(geometricRotation, alignmentRotation);
					// Calculate the child jigsaw's position after applying the FINAL rotation
					// CRITICAL: We use the ORIGINAL (unrotated) child jigsaw position
					// CoordinateConverter.rotate() expects positions in the original structure space
					Vector3Int rotatedJigsawPos = CoordinateConverter.rotate(
							childJigsaw.position(), // ORIGINAL position from NBT
							finalRotation, // FINAL combined rotation
							structureData.size());
					// Calculate the connection offset (one block in the direction parent jigsaw faces)
					Vector3Int connectionOffset = getConnectionOffset(pending.connection().orientation());
					// Calculate where the child structure's origin must be placed
					// Formula: childPos + rotatedJigsawPos = parentPos + offset
					// Solving: childPos = parentPos + offset - rotatedJigsawPos
					Vector3Int finalPosition = Vector3Int.of(
							pending.connection().position().getX() + connectionOffset.getX() - rotatedJigsawPos.getX(),
							pending.connection().position().getY() + connectionOffset.getY() - rotatedJigsawPos.getY(),
							pending.connection().position().getZ() + connectionOffset.getZ() - rotatedJigsawPos.getZ());
					if (DEBUG_CONNECTIONS) {
						LOGGER.info(String.format("  Testing: geoRot=%s, alignRot=%s, finalRot=%s",
								geometricRotation.getDegrees(),
								alignmentRotation.getDegrees(),
								finalRotation.getDegrees()));
						LOGGER.info(String.format("    Position: (xyz=%s,%s,%s), rotatedJigsawPos: (xyz=%s,%s,%s)",
								finalPosition.getX(),
								finalPosition.getY(),
								finalPosition.getZ(),
								rotatedJigsawPos.getX(),
								rotatedJigsawPos.getY(),
								rotatedJigsawPos.getZ()));
					}
					if (this.debugContext != null && this.debugContext.isRunning()) {
						this.debugContext.setGeometricRotation(geometricRotation);
						this.debugContext.setAlignmentRotation(alignmentRotation);
						this.debugContext.setCurrentStructure(structureData);
						this.debugContext.setCurrentPosition(finalPosition);
						this.debugContext.setCurrentElementFile(nbtFile);
						this.debugContext.setHasCollision(false);
					}
					// Check collision using the final rotation
					// AABB.fromPiece() will rotate all 8 corners using finalRotation
					AABB childBounds = AABB.fromPiece(
							finalPosition,
							structureData.size(),
							finalRotation);
					boolean hasCollision = collides(childBounds);
					if (this.debugContext != null && this.debugContext.isRunning()) {
						this.debugContext.setHasCollision(hasCollision);
						this.debugContext.pause("POST_COLLISION_CHECK",
								hasCollision ? "COLLISION DETECTED" : "CLEAR TO PLACE");
					}
					if (!hasCollision) {
						// Transform all jigsaw blocks using the final rotation
						List<TransformedJigsawBlock> connections = transformJigsawBlocks(
								structureData.jigsawBlocks(),
								finalPosition,
								finalRotation,
								structureData.size());
						// Calculate where this specific child jigsaw ended up in world space
						Vector3Int consumedChildPosition = calculateTransformedJigsawPosition(
								childJigsaw,
								finalPosition,
								finalRotation,
								structureData.size());
						if (DEBUG_CONNECTIONS) {
							LOGGER.info(String.format("  Marking child jigsaw as consumed at world pos: %s",
									consumedChildPosition));
						}
						connectionRegistry.markConsumed(consumedChildPosition);
						successfulConnections++;
						usageTracker.recordPlacement(pool.getId(), nbtFile);
						PlacedJigsawPiece childPiece = new PlacedJigsawPiece(
								nbtFile,
								finalPosition,
								finalRotation,
								structureData,
								connections,
								pending.depth() + 1,
								pending.sourcePiece(),
								pool.getId());
						if (this.debugContext != null && this.debugContext.isRunning()) {
							World world = this.debugContext.getWorld();
							if (world != null) {
								DebugVisualizer.visualizePlacedPiece(world, this.debugContext, childPiece);
							}
							this.debugContext.setActiveConnectionPoint(null);
						}
						LOGGER.info(String.format(
								"PLACED piece: file=%s, pos=(xyz=%s,%s,%s), geoRot=%s, alignRot=%s, finalRot=%s, bounds=%s",
								nbtFile,
								finalPosition.getX(),
								finalPosition.getY(),
								finalPosition.getZ(),
								geometricRotation.getDegrees(),
								alignmentRotation.getDegrees(),
								finalRotation.getDegrees(),
								childBounds));
						return childPiece;
					}
				}
			}
		}
		return null;
	}

	private JigsawData.JigsawBlock rotateJigsawBlock(
			JigsawData.JigsawBlock jigsaw,
			Rotation rotation,
			Vector3Int structureSize) {
		if (rotation == Rotation.NONE) {
			return jigsaw;
		}
		// Rotate position
		Vector3Int rotatedPos = CoordinateConverter.rotate(
				jigsaw.position(),
				rotation,
				structureSize);
		// Rotate orientation
		String rotatedOrientation = TransformUtil.rotateOrientation(
				jigsaw.orientation(),
				rotation);
		return new JigsawData.JigsawBlock(rotatedPos, rotatedOrientation, jigsaw.info());
	}

	/**
	 * Calculates the rotation needed to align two jigsaw orientations.
	 * The child's orientation (after any geometric rotation) must be rotated to face opposite the
	 * parent's.
	 * 
	 * IMPORTANT: This should always be called with the UNROTATED child orientation.
	 * Geometric rotation is applied separately and combined afterward.
	 * 
	 * @param parentOrientation
	 *            The orientation of the parent jigsaw (in world space)
	 * @param childOrientation
	 *            The orientation of the child jigsaw (in structure-local space, UNROTATED)
	 * @return The alignment rotation to apply
	 */
	private Rotation calculateRequiredRotation(String parentOrientation, String childOrientation) {
		// Parse orientations
		String[] parentParts = parentOrientation.toLowerCase().split("_");
		String parentPrimary = parentParts[0];
		String parentSecondary = parentParts.length > 1 ? parentParts[1] : "north";

		String[] childParts = childOrientation.toLowerCase().split("_");
		String childPrimary = childParts[0];
		String childSecondary = childParts.length > 1 ? childParts[1] : "north";

		// If vertical connection, align secondary orientations
		if ("up".equals(parentPrimary) || "down".equals(parentPrimary)) {
			return calculateRotationBetweenDirections(childSecondary, parentSecondary);
		}

		// If horizontal, child must face opposite to parent
		String targetPrimary = getOppositeDirection(parentPrimary);
		return calculateRotationBetweenDirections(childPrimary, targetPrimary);
	}

	/**
	 * Gets the opposite direction for a given direction.
	 */
	private String getOppositeDirection(String direction) {
		return switch (direction.toLowerCase()) {
			case "north" -> "south";
			case "south" -> "north";
			case "east" -> "west";
			case "west" -> "east";
			case "up" -> "down";
			case "down" -> "up";
			default -> direction;
		};
	}

	/**
	 * Calculates the rotation needed to turn from one direction to another.
	 * Only handles horizontal directions (north, east, south, west).
	 */
	private Rotation calculateRotationBetweenDirections(String from, String to) {
		if (from.equals(to)) {
			return Rotation.NONE;
		}

		int fromIndex = directionToIndex(from);
		int toIndex = directionToIndex(to);

		if (fromIndex == -1 || toIndex == -1) {
			return Rotation.NONE;
		}

		int steps = (toIndex - fromIndex + 4) % 4;

		return switch (steps) {
			case 0 -> Rotation.NONE;
			case 1 -> Rotation.CW_90;
			case 2 -> Rotation.CW_180;
			case 3 -> Rotation.CCW_90;
			default -> Rotation.NONE;
		};
	}

	/**
	 * Converts a direction string to an index (north=0, east=1, south=2, west=3).
	 */
	private int directionToIndex(String direction) {
		return switch (direction.toLowerCase()) {
			case "north" -> 0;
			case "east" -> 1;
			case "south" -> 2;
			case "west" -> 3;
			default -> -1;
		};
	}

	/**
	 * Gets the offset to apply when connecting two jigsaw blocks.
	 * This accounts for the fact that jigsaws connect face-to-face.
	 */
	private Vector3Int getConnectionOffset(String parentOrientation) {
		String primaryDir = parentOrientation.toLowerCase().split("_")[0];

		return switch (primaryDir) {
			case "north" -> Vector3Int.of(0, 0, -1);
			case "south" -> Vector3Int.of(0, 0, 1);
			case "east" -> Vector3Int.of(1, 0, 0);
			case "west" -> Vector3Int.of(-1, 0, 0);
			case "up" -> Vector3Int.of(0, 1, 0);
			case "down" -> Vector3Int.of(0, -1, 0);
			default -> Vector3Int.of(0, 0, 0);
		};
	}

	/**
	 * Combines two rotations into a single rotation.
	 * This is simple modulo-4 addition of rotation steps.
	 */
	private Rotation combineRotations(Rotation first, Rotation second) {
		if (first == Rotation.NONE)
			return second;
		if (second == Rotation.NONE)
			return first;

		int totalSteps = (rotationToSteps(first) + rotationToSteps(second)) % 4;
		return stepsToRotation(totalSteps);
	}

	/**
	 * Converts a Rotation to number of 90-degree clockwise steps.
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
	 * Converts number of 90-degree clockwise steps to a Rotation.
	 */
	private static Rotation stepsToRotation(int steps) {
		return switch (steps % 4) {
			case 0 -> Rotation.NONE;
			case 1 -> Rotation.CW_90;
			case 2 -> Rotation.CW_180;
			case 3 -> Rotation.CCW_90;
			default -> Rotation.NONE;
		};
	}

	/**
	 * Gets the list of rotations to try based on joint type.
	 */
	private List<Rotation> getRotationsForJoint(JigsawData.JointType jointType) {
		if (jointType == JigsawData.JointType.ROLLABLE) {
			return List.of(Rotation.NONE, Rotation.CW_90, Rotation.CW_180, Rotation.CCW_90);
		} else {
			return List.of(Rotation.NONE);
		}
	}

	/**
	 * Selects candidate files from a pool while respecting maximum count constraints
	 */
	private List<String> selectCandidateFilesRespectingMaxCounts(JigsawPool pool, int count) {
		List<String> candidates = new ArrayList<>();
		Set<String> selected = new HashSet<>();
		int attempts = 0;
		int maxAttempts = count * 3;
		while (candidates.size() < count && attempts++ < maxAttempts) {
			Set<String> excludedFiles = new HashSet<>();
			for (JigsawPool.WeightedElement element : pool.getElements()) {
				int currentCount = usageTracker.getCount(pool.getId(), element.getFile());
				int pendingCount = (int) candidates.stream()
						.filter(c -> c.equals(element.getFile()))
						.count();
				if (currentCount + pendingCount >= element.getMaxCount()) {
					excludedFiles.add(element.getFile());
				}
			}
			if (excludedFiles.size() == pool.getElements().size()) {
				LOGGER.info("All elements in pool " + pool.getId() + " have reached max count");
				break;
			}
			String file = pool.selectRandomElementWithExclusions(random, excludedFiles);
			if (file == null) {
				break;
			}
			if (!selected.contains(file)) {
				candidates.add(file);
				selected.add(file);
			}
		}
		return candidates;
	}

	/**
	 * Ensures minimum piece counts are met for all constrained elements
	 */
	private void ensureMinimumPieceCounts() {
		if (allConstraints.isEmpty()) {
			return;
		}
		for (UsageConstraints constraint : allConstraints) {
			if (!constraint.hasMinimum()) {
				continue;
			}
			int currentCount = usageTracker.getCount(constraint.poolId(), constraint.elementFile());
			if (currentCount < constraint.minCount()) {
				switch (enforcementStrategy) {
					case STRICT:
						handleStrictEnforcement(constraint, currentCount);
						break;
					case BEST_EFFORT:
						handleBestEffortEnforcement(constraint, currentCount);
						break;
					case FLEXIBLE:
						handleFlexibleEnforcement(constraint, currentCount);
						break;
				}
			}
		}
		if (!capturedViolations.isEmpty() && enforcementStrategy == EnforcementStrategy.STRICT) {
			LOGGER.severe("Generation FAILED due to " + capturedViolations.size() + " constraint violations:");
			for (ConstraintViolation violation : capturedViolations) {
				LOGGER.severe("  - " + violation.getMessage());
			}
			pieces.clear();
		}
	}

	private void handleStrictEnforcement(UsageConstraints constraint, int currentCount) {
		ForcedPlacement.ForcedPlacementResult result = forcedPlacement.forcePlacementsForMinimum(
				constraint,
				currentCount,
				pieces,
				occupiedSpace);
		int finalCount = currentCount + result.pieces().size();
		if (finalCount < constraint.minCount()) {
			capturedViolations.add(new ConstraintViolation(
					constraint,
					finalCount,
					ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
		} else {
			pieces.addAll(result.pieces());
			// Mark consumed connections in registry instead of mutating pieces
			for (ForcedPlacement.ConnectionUsage usage : result.consumedConnections()) {
				connectionRegistry.markConsumed(usage.connectionPosition());
			}
			for (PlacedJigsawPiece piece : result.pieces()) {
				usageTracker.recordPlacement(constraint.poolId(), piece.nbtFile());
			}
		}
	}

	private void handleBestEffortEnforcement(UsageConstraints constraint, int currentCount) {
		ForcedPlacement.ForcedPlacementResult result = forcedPlacement.forcePlacementsForMinimum(
				constraint,
				currentCount,
				pieces,
				occupiedSpace);
		if (!result.pieces().isEmpty()) {
			pieces.addAll(result.pieces());
			// Mark consumed connections in registry
			for (ForcedPlacement.ConnectionUsage usage : result.consumedConnections()) {
				connectionRegistry.markConsumed(usage.connectionPosition());
			}
			for (PlacedJigsawPiece piece : result.pieces()) {
				usageTracker.recordPlacement(constraint.poolId(), piece.nbtFile());
			}
		}
		int finalCount = currentCount + result.pieces().size();
		if (finalCount < constraint.minCount()) {
			capturedViolations.add(new ConstraintViolation(
					constraint,
					finalCount,
					ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
			LOGGER.warning(String.format(
					"Best-effort: %s minimum not fully met (achieved: %d/%d)",
					constraint.elementFile(),
					finalCount,
					constraint.minCount()));
		}
	}

	private void handleFlexibleEnforcement(UsageConstraints constraint, int currentCount) {
		if (currentCount < constraint.minCount()) {
			capturedViolations.add(new ConstraintViolation(
					constraint,
					currentCount,
					ConstraintViolation.ViolationType.MINIMUM_NOT_MET));
		}
		LOGGER.fine(String.format(
				"Flexible strategy: accepting %d pieces for %s (target was %d)",
				currentCount,
				constraint.elementFile(),
				constraint.minCount()));
	}

	private void queueConnections(PlacedJigsawPiece piece, int depth) {
		if (DEBUG_CONNECTIONS) {
			LOGGER.info(String.format("QUEUEING connections from piece at %s (depth=%d, file=%s)",
					piece.worldPosition(), depth, piece.nbtFile()));
		}
		for (TransformedJigsawBlock connection : piece.connections()) {
			// Check registry instead of connection.isConsumed()
			if (!connectionRegistry.isConsumed(connection.position())) {
				PendingJigsawConnection pending = PendingJigsawConnection.create(
						connection,
						piece,
						depth);
				connectionQueue.offer(pending);
				if (DEBUG_CONNECTIONS) {
					LOGGER.info(String.format("  Queued: pos=(xyz=%s,%s,%s), orientation=%s, target=%s, priority=%d",
							connection.position().getX(),
							connection.position().getY(),
							connection.position().getZ(),
							connection.orientation(),
							connection.info().target(),
							pending.priority()));
				}
			} else {
				if (DEBUG_CONNECTIONS) {
					LOGGER.info(String.format("  Skipped (consumed): pos=(xyz=%s,%s,%s)",
							connection.position().getX(),
							connection.position().getY(),
							connection.position().getZ()));
				}
			}
		}
	}

	private List<TransformedJigsawBlock> transformJigsawBlocks(
			List<JigsawData.JigsawBlock> jigsawBlocks,
			Vector3Int worldPosition,
			Rotation rotation,
			Vector3Int structureSize) {
		if (DEBUG_CONNECTIONS) {
			LOGGER.info(String.format("Transforming %d jigsaws: worldPos=(xyz=%s,%s,%s), rotation=%s, size=%s",
					jigsawBlocks.size(),
					worldPosition.getX(),
					worldPosition.getY(),
					worldPosition.getZ(),
					rotation.getDegrees(),
					structureSize));
		}
		return jigsawBlocks.stream()
				.map(jigsaw -> {
					TransformedJigsawBlock transformed = TransformUtil.transformJigsawConnection(
							jigsaw,
							worldPosition,
							rotation,
							structureSize);
					if (DEBUG_CONNECTIONS) {
						LOGGER.info(String.format("  Transformed: local=(xyz=%s,%s,%s) -> world=(xyz=%s,%s,%s), orientation=%s -> %s",
								jigsaw.position().getX(),
								jigsaw.position().getY(),
								jigsaw.position().getZ(),
								transformed.position().getX(),
								transformed.position().getY(),
								transformed.position().getZ(),
								jigsaw.orientation(),
								transformed.orientation()));
					}
					return transformed;
				})
				.collect(Collectors.toList());
	}

	private boolean collides(AABB bounds) {
		for (AABB occupied : occupiedSpace) {
			if (bounds.intersects(occupied)) {
				return true;
			}
		}
		return false;
	}

	private Rotation selectRandomRotation() {
		Rotation[] rotations = Rotation.values();
		return rotations[random.nextInt(rotations.length)];
	}

	public GenerationStatistics getStatistics() {
		List<String> violationMessages = capturedViolations.stream()
				.map(ConstraintViolation::getMessage)
				.toList();
		long generationTime = System.currentTimeMillis() - generationStartTime;
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
				violationMessages,
				attemptedConnections,
				successfulConnections);
	}

	public PoolUsageTracker getUsageTracker() {
		return usageTracker;
	}
}