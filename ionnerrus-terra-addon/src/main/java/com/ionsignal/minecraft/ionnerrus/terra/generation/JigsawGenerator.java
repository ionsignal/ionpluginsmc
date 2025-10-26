package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.util.Rotation;
import com.dfsek.terra.api.registry.key.RegistryKey;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.*;
import com.ionsignal.minecraft.ionnerrus.terra.model.JigsawData;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.provider.NBTStructureProvider;
import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;
import com.ionsignal.minecraft.ionnerrus.terra.util.TransformUtil;
import com.ionsignal.minecraft.ionnerrus.terra.core.JigsawConnection;

import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * Main class for the recursive jigsaw structure generation algorithm.
 * Generates a complete JigsawPlacement from a starting pool and configuration.
 */
public class JigsawGenerator {
	private static final Logger LOGGER = Logger.getLogger(JigsawGenerator.class.getName());

	private final ConfigPack pack;
	private final Platform platform;
	private final Random random;
	private final JigsawStructureTemplate config;
	private final Vector3Int origin;
	private final PoolRegistry poolRegistry;

	private final List<PlacedJigsawPiece> pieces;
	private final PriorityQueue<PendingJigsawConnection> connectionQueue;
	private final Set<AABB> occupiedSpace;

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
	}

	/**
	 * Generates a complete jigsaw structure placement.
	 * 
	 * @param startPoolId
	 *            The ID of the starting pool
	 * @return A JigsawPlacement containing all placed pieces
	 */
	public JigsawPlacement generate(String startPoolId) {
		// 1. Select and place initial piece from start pool
		PlacedJigsawPiece startPiece = selectAndPlaceStartPiece(startPoolId);
		if (startPiece == null) {
			// ADDED: Log warning when no start piece can be placed
			LOGGER.warning("Failed to place start piece from pool: " + startPoolId);
			return JigsawPlacement.empty(origin, config.getID());
		}

		pieces.add(startPiece);
		occupiedSpace.add(startPiece.getWorldBounds());
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
		// 3. Ensure minimum pool requirements are met
		ensureMinimumPieceCounts();
		return new JigsawPlacement(pieces, origin, config.getID());
	}

	/**
	 * Selects and places the starting piece.
	 */
	private PlacedJigsawPiece selectAndPlaceStartPiece(String startPoolId) {
		JigsawPool startPool = poolRegistry.getPool(startPoolId);
		if (startPool == null) {
			// ADDED: Log error when pool not found
			LOGGER.severe("Start pool not found: " + startPoolId);
			return null;
		}
		String nbtFile = startPool.selectRandomElement(random);
		if (nbtFile == null) {
			// ADDED: Log warning when pool is empty
			LOGGER.warning("Start pool is empty: " + startPoolId);
			return null;
		}
		NBTStructure.StructureData structureData = NBTStructureProvider.getInstance().load(pack, nbtFile);
		if (structureData == null) {
			// ADDED: Log error when structure file can't be loaded
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
		return PlacedJigsawPiece.createStartPiece(
				nbtFile,
				origin,
				rotation,
				structureData,
				connections);
	}

	/**
	 * Attempts to place a piece connecting to the given connection point.
	 */
	private PlacedJigsawPiece tryPlaceConnectingPiece(PendingJigsawConnection pending) {
		// Get target pool
		String targetPoolId = pending.getTargetPool();
		JigsawPool pool = poolRegistry.getPool(targetPoolId);
		if (pool == null) {
			// ADDED: Debug logging for missing pools
			LOGGER.fine("Target pool not found: " + targetPoolId);
			return null;
		}
		// Try multiple pieces from the pool (in case some don't fit)
		List<String> candidateFiles = selectCandidateFiles(pool, 5);
		for (String nbtFile : candidateFiles) {
			NBTStructure.StructureData structureData = NBTStructureProvider.getInstance().load(pack, nbtFile);
			if (structureData == null) {
				continue;
			}
			// Find a compatible jigsaw in the child structure
			JigsawData.JigsawBlock compatibleJigsaw = findCompatibleJigsaw(structureData.jigsawBlocks(), pending.connection());
			if (compatibleJigsaw == null) {
				continue;
			}
			// Calculate alignment transform
			PlacementTransform transform = TransformUtil.calculateAlignment(
					pending.connection(),
					compatibleJigsaw,
					structureData.size());
			// Check collision with existing pieces
			AABB childBounds = AABB.fromPiece(
					transform.position(),
					structureData.size(),
					transform.rotation());
			if (!collides(childBounds)) {
				// Transform jigsaw blocks to world space
				List<TransformedJigsawBlock> connections = transformJigsawBlocks(
						structureData.jigsawBlocks(),
						transform.position(),
						transform.rotation(),
						structureData.size());
				// MODIFIED: Include parent reference when creating child piece
				return new PlacedJigsawPiece(
						nbtFile,
						transform.position(),
						transform.rotation(),
						structureData,
						connections,
						pending.depth() + 1,
						pending.sourcePiece()); // ADDED: parent reference
			}
		}

		return null;
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
	 * Finds a compatible jigsaw block in a structure for connection.
	 */
	private JigsawData.JigsawBlock findCompatibleJigsaw(
			List<JigsawData.JigsawBlock> jigsawBlocks,
			TransformedJigsawBlock targetConnection) {
		for (JigsawData.JigsawBlock jigsaw : jigsawBlocks) {
			// Check if this jigsaw can connect to the target
			if (JigsawConnection.matchesConnectionName(
					jigsaw.info().target(),
					targetConnection.info().name())) {
				return jigsaw;
			}
		}
		return null;
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
}