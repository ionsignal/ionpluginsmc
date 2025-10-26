package com.ionsignal.minecraft.ionnerrus.terra.generation.placements;

import com.dfsek.terra.api.util.vector.Vector3Int;

/**
 * Represents a jigsaw connection point that is queued for processing during generation.
 * Used internally by the JigsawGenerator to manage the expansion of the structure.
 * 
 * @param connection
 *            The transformed jigsaw block in world space
 * @param sourcePiece
 *            The piece that owns this connection
 * @param depth
 *            The current generation depth when this connection was queued
 * @param priority
 *            The processing priority (higher = process earlier)
 */
public record PendingJigsawConnection(
		TransformedJigsawBlock connection,
		PlacedJigsawPiece sourcePiece,
		int depth,
		int priority) implements Comparable<PendingJigsawConnection> {

	/**
	 * Creates a PendingJigsawConnection with validation.
	 */
	public PendingJigsawConnection {
		if (connection == null) {
			throw new IllegalArgumentException("Connection cannot be null");
		}
		if (sourcePiece == null) {
			throw new IllegalArgumentException("Source piece cannot be null");
		}
		if (depth < 0) {
			throw new IllegalArgumentException("Depth cannot be negative");
		}
	}

	/**
	 * Factory method that calculates priority from the connection.
	 */
	public static PendingJigsawConnection create(
			TransformedJigsawBlock connection,
			PlacedJigsawPiece sourcePiece,
			int depth) {
		return new PendingJigsawConnection(connection, sourcePiece, depth, connection.getPriority());
	}

	/**
	 * Compares connections for priority queue ordering.
	 * Higher priority values are processed first.
	 * If priorities are equal, prefer shallower depths (breadth-first tendency).
	 */
	@Override
	public int compareTo(PendingJigsawConnection other) {
		// First compare by priority (higher first)
		int priorityCompare = Integer.compare(other.priority, this.priority);
		if (priorityCompare != 0) {
			return priorityCompare;
		}

		// Then by depth (shallower first for breadth-first tendency)
		int depthCompare = Integer.compare(this.depth, other.depth);
		if (depthCompare != 0) {
			return depthCompare;
		}

		// Finally by connection name for deterministic ordering
		return this.connection.info().name().compareTo(other.connection.info().name());
	}

	/**
	 * Checks if this connection has exceeded the maximum depth.
	 * 
	 * @param maxDepth
	 *            The maximum allowed depth
	 * @return true if this connection should be skipped due to depth
	 */
	public boolean exceedsDepth(int maxDepth) {
		return depth >= maxDepth;
	}

	/**
	 * Checks if this connection is too far from an origin point.
	 * 
	 * @param origin
	 *            The origin point
	 * @param maxDistance
	 *            The maximum allowed distance
	 * @return true if this connection should be skipped due to distance
	 */
	public boolean exceedsDistance(Vector3Int origin, int maxDistance) {
		Vector3Int pos = connection.position();
		int dx = pos.getX() - origin.getX();
		int dz = pos.getZ() - origin.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		return distance > maxDistance;
	}

	/**
	 * Gets the target pool ID for this connection.
	 */
	public String getTargetPool() {
		return connection.info().target();
	}

	/**
	 * Checks if this connection targets an empty pool (terminator).
	 */
	public boolean isTerminator() {
		String target = connection.info().target();
		return "minecraft:empty".equals(target) || target.isEmpty();
	}
}