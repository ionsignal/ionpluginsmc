package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.core.JigsawConnection;
import com.ionsignal.minecraft.ionnerrus.terra.generation.debug.DebugContext;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ConstraintViolation;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.EnforcementStrategy;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ForcedPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.JigsawPlacement;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PendingJigsawConnection;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacementTransform;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.TransformedJigsawBlock;
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

	private final EnforcementStrategy enforcementStrategy;
	private final List<UsageConstraints> allConstraints;
	private final ForcedPlacement forcedPlacement;

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
			return JigsawPlacement.empty(origin, config.getID());
		}
		pieces.add(startPiece);
		occupiedSpace.add(startPiece.getWorldBounds());
		usageTracker.recordPlacement(startPiece.sourcePoolId(), startPiece.nbtFile());
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
				updateConnectionAsConsumed(pending);
			}
		}
		ensureMinimumPieceCounts();
		long generationTime = System.currentTimeMillis() - generationStartTime;
		LOGGER.info(String.format(
				"Generation completed in %dms: %d pieces placed, %d/%d connections successful%s",
				generationTime,
				pieces.size(),
				successfulConnections,
				attemptedConnections,
				capturedViolations.isEmpty() ? "" : " (" + capturedViolations.size() + " violations)"));
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
		Rotation rotation = selectRandomRotation();
		List<TransformedJigsawBlock> connections = transformJigsawBlocks(
				structureData.jigsawBlocks(),
				origin,
				rotation,
				structureData.size());
		return PlacedJigsawPiece.createStartPiece(nbtFile, origin, rotation, structureData, connections, startPoolId);
	}

	/**
	 * Attempts to place a piece at a pending connection point.
	 * Thread-safe: Only sets visualization data, doesn't call Bukkit APIs.
	 */
	private PlacedJigsawPiece tryPlaceConnectingPiece(PendingJigsawConnection pending) {
		attemptedConnections++;
		if (this.debugContext != null && this.debugContext.isRunning()) {
			this.debugContext.setCurrentPiece(pending.sourcePiece());
			this.debugContext.setCurrentPoolId(pending.getTargetPoolId());
			this.debugContext.setActiveConnectionPoint(pending.connection().position());
		}
		String targetPoolId = pending.getTargetPoolId();
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
				if (!JigsawConnection.matchesConnectionName(pending.getTargetName(), childJigsaw.info().name()) ||
						!JigsawConnection.matchesConnectionName(childJigsaw.info().target(), pending.connection().info().name())) {
					continue;
				}
				List<Rotation> rotationsToTry = getRotationsForJoint(childJigsaw.info().jointType());
				for (Rotation geometricRotation : rotationsToTry) {
					if (this.debugContext != null && this.debugContext.isRunning()) {
						this.debugContext.setGeometricRotation(geometricRotation);
						this.debugContext.setCurrentElementFile(nbtFile);
					}
					JigsawData.JigsawBlock rotatedChildJigsaw = rotateJigsawBlock(
							childJigsaw,
							geometricRotation,
							structureData.size());
					PlacementTransform alignmentTransform = TransformUtil.calculateAlignment(
							pending.connection(),
							rotatedChildJigsaw,
							structureData.size());
					Rotation finalRotation = combineRotations(geometricRotation, alignmentTransform.rotation());
					Vector3Int finalPosition = alignmentTransform.position();
					if (this.debugContext != null && this.debugContext.isRunning()) {
						this.debugContext.setAlignmentRotation(alignmentTransform.rotation());
						this.debugContext.setCurrentStructure(structureData);
						this.debugContext.setCurrentPosition(finalPosition);
						this.debugContext.setHasCollision(false);
					}
					AABB childBounds = AABB.fromPiece(
							finalPosition,
							structureData.size(),
							finalRotation);
					boolean hasCollision = collides(childBounds);
					if (this.debugContext != null && this.debugContext.isRunning()) {
						this.debugContext.setHasCollision(hasCollision);
						this.debugContext.pause("POST_COLLISION_CHECK", hasCollision ? "COLLISION DETECTED" : "CLEAR TO PLACE");
					}
					if (!hasCollision) {
						List<TransformedJigsawBlock> connections = transformJigsawBlocks(
								structureData.jigsawBlocks(),
								finalPosition,
								finalRotation,
								structureData.size());
						successfulConnections++;
						usageTracker.recordPlacement(targetPoolId, nbtFile);
						LOGGER.info(String.format(
								"Placed piece: file=%s, pos=%s, geoRot=%s, alignRot=%s, finalRot=%s, bounds=%s",
								nbtFile,
								finalPosition,
								geometricRotation,
								alignmentTransform.rotation(),
								finalRotation,
								childBounds));
						if (this.debugContext != null && this.debugContext.isRunning()) {
							this.debugContext.setActiveConnectionPoint(null);
						}
						return new PlacedJigsawPiece(
								nbtFile,
								finalPosition,
								finalRotation,
								structureData,
								connections,
								pending.depth() + 1,
								pending.sourcePiece(),
								targetPoolId);
					}
				}
			}
		}
		return null;
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
			for (ForcedPlacement.ConnectionUsage usage : result.consumedConnections()) {
				markConnectionAsConsumed(usage.parentPiece(), usage.connectionPosition());
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
			for (ForcedPlacement.ConnectionUsage usage : result.consumedConnections()) {
				markConnectionAsConsumed(usage.parentPiece(), usage.connectionPosition());
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

	private void markConnectionAsConsumed(PlacedJigsawPiece parentPiece, Vector3Int connectionPosition) {
		for (int i = 0; i < pieces.size(); i++) {
			if (pieces.get(i) == parentPiece) {
				PlacedJigsawPiece updated = pieces.get(i).withConsumedConnection(connectionPosition);
				pieces.set(i, updated);
				break;
			}
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

	private JigsawData.JigsawBlock rotateJigsawBlock(JigsawData.JigsawBlock jigsaw, Rotation rotation, Vector3Int structureSize) {
		if (rotation == Rotation.NONE) {
			return jigsaw;
		}
		Vector3Int rotatedPos = CoordinateConverter.rotate(jigsaw.position(), rotation, structureSize);
		String rotatedOrientation = TransformUtil.rotateOrientation(jigsaw.orientation(), rotation);
		return new JigsawData.JigsawBlock(rotatedPos, rotatedOrientation, jigsaw.info());
	}

	private List<Rotation> getRotationsForJoint(JigsawData.JointType jointType) {
		if (jointType == JigsawData.JointType.ROLLABLE) {
			return List.of(Rotation.NONE, Rotation.CW_90, Rotation.CW_180, Rotation.CCW_90);
		} else {
			return List.of(Rotation.NONE);
		}
	}

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

	private int rotationToSteps(Rotation rotation) {
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

	private void updateConnectionAsConsumed(PendingJigsawConnection pending) {
		for (int i = 0; i < pieces.size(); i++) {
			if (pieces.get(i) == pending.sourcePiece()) {
				PlacedJigsawPiece updated = pieces.get(i).withConsumedConnection(
						pending.connection().position());
				pieces.set(i, updated);
				break;
			}
		}
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