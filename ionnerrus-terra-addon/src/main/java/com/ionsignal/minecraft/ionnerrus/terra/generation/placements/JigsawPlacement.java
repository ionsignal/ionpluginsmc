package com.ionsignal.minecraft.ionnerrus.terra.generation.placements;

import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;
import com.dfsek.terra.api.util.vector.Vector3Int;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a complete jigsaw structure that has been generated and planned.
 * This is the "town plan" containing all pieces and their placements.
 * 
 * @param pieces
 *            All structure pieces that make up this placement
 * @param origin
 *            The world position where generation started
 * @param structureId
 *            The ID of the structure configuration
 * @param totalBounds
 *            The combined AABB of all pieces
 */
public record JigsawPlacement(
		List<PlacedJigsawPiece> pieces,
		Vector3Int origin,
		String structureId,
		AABB totalBounds) {

	/**
	 * Creates a JigsawPlacement with validation and automatic bounds calculation.
	 */
	public JigsawPlacement(List<PlacedJigsawPiece> pieces, Vector3Int origin, String structureId) {
		this(
				pieces != null ? List.copyOf(pieces) : Collections.emptyList(),
				origin,
				structureId,
				calculateTotalBounds(pieces));
	}

	/**
	 * Creates a JigsawPlacement with validation.
	 */
	public JigsawPlacement {
		if (pieces == null) {
			pieces = Collections.emptyList();
		} else {
			pieces = List.copyOf(pieces); // Ensure immutability
		}
		if (origin == null) {
			throw new IllegalArgumentException("Origin cannot be null");
		}
		if (structureId == null || structureId.isEmpty()) {
			throw new IllegalArgumentException("Structure ID cannot be null or empty");
		}
	}

	/**
	 * Calculates the combined bounding box of all pieces.
	 */
	private static AABB calculateTotalBounds(List<PlacedJigsawPiece> pieces) {
		if (pieces == null || pieces.isEmpty()) {
			return new AABB(Vector3Int.of(0, 0, 0), Vector3Int.of(0, 0, 0));
		}

		AABB bounds = pieces.get(0).getWorldBounds();
		for (int i = 1; i < pieces.size(); i++) {
			bounds = bounds.expandToInclude(pieces.get(i).getWorldBounds());
		}
		return bounds;
	}

	/**
	 * Creates an empty placement (no pieces).
	 */
	public static JigsawPlacement empty(Vector3Int origin, String structureId) {
		return new JigsawPlacement(
				Collections.emptyList(),
				origin,
				structureId,
				new AABB(origin, origin));
	}

	/**
	 * Gets all pieces that intersect with a specific chunk.
	 * 
	 * @param chunkX
	 *            The chunk X coordinate
	 * @param chunkZ
	 *            The chunk Z coordinate
	 * @return Stream of pieces that overlap the chunk
	 */
	public Stream<PlacedJigsawPiece> getPiecesInChunk(int chunkX, int chunkZ) {
		// Quick check: if total bounds don't intersect chunk, no pieces will
		if (!totalBounds.intersectsChunkRegion(chunkX, chunkZ)) {
			return Stream.empty();
		}

		return pieces.stream()
				.filter(piece -> piece.intersectsChunk(chunkX, chunkZ));
	}

	/**
	 * Gets the total number of pieces in this placement.
	 */
	public int getPieceCount() {
		return pieces.size();
	}

	/**
	 * Gets the maximum depth reached during generation.
	 */
	public int getMaxDepth() {
		return pieces.stream()
				.mapToInt(PlacedJigsawPiece::depth)
				.max()
				.orElse(0);
	}

	/**
	 * Checks if this placement is empty (has no pieces).
	 */
	public boolean isEmpty() {
		return pieces.isEmpty();
	}

	/**
	 * Gets the start piece (depth 0).
	 * 
	 * @return The start piece, or null if placement is empty
	 */
	public PlacedJigsawPiece getStartPiece() {
		return pieces.stream()
				.filter(piece -> piece.depth() == 0)
				.findFirst()
				.orElse(null);
	}

	/**
	 * Gets all pieces at a specific depth level.
	 * 
	 * @param depth
	 *            The depth level to query
	 * @return List of pieces at that depth
	 */
	public List<PlacedJigsawPiece> getPiecesAtDepth(int depth) {
		return pieces.stream()
				.filter(piece -> piece.depth() == depth)
				.toList();
	}

	/**
	 * Calculates the total number of blocks in this placement.
	 * Useful for performance metrics and debugging.
	 */
	public int getTotalBlockCount() {
		return pieces.stream()
				.mapToInt(piece -> piece.structureData().blocks().size())
				.sum();
	}

	/**
	 * Gets a breakdown of pool usage in this placement.
	 * 
	 * @return Map of poolId -> count of pieces from that pool
	 */
	public Map<String, Integer> getPoolUsageBreakdown() {
		return pieces.stream()
				.filter(piece -> piece.sourcePoolId() != null) // PHASE 1: Handle null for simple structures
				.collect(Collectors.groupingBy(
						PlacedJigsawPiece::sourcePoolId,
						Collectors.summingInt(p -> 1)));
	}

	/**
	 * Gets a breakdown by specific element file.
	 * 
	 * @return Map of elementFile -> count
	 */
	public Map<String, Integer> getElementUsageBreakdown() {
		return pieces.stream()
				.collect(Collectors.groupingBy(
						PlacedJigsawPiece::nbtFile,
						Collectors.summingInt(p -> 1)));
	}

	/**
	 * Gets a summary string for logging.
	 */
	public String getSummary() {
		Map<String, Integer> poolUsage = getPoolUsageBreakdown();
		String poolSummary = poolUsage.isEmpty()
				? "N/A (simple structure)"
				: poolUsage.toString();

		return String.format(
				"JigsawPlacement[id=%s, pieces=%d, maxDepth=%d, bounds=%s, blocks=%d, pools=%s]",
				structureId,
				getPieceCount(),
				getMaxDepth(),
				totalBounds,
				getTotalBlockCount(),
				poolSummary);
	}
}