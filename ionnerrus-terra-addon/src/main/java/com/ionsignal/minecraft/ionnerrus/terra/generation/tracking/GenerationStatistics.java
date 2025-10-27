package com.ionsignal.minecraft.ionnerrus.terra.generation.tracking;

import java.util.Map;
import java.util.List;

/**
 * Immutable snapshot of generation statistics for a completed structure.
 * Used for diagnostics, debugging, and optimization analysis.
 * 
 * @param structureId
 *            The ID of the structure that was generated
 * @param totalPieces
 *            Total number of pieces placed
 * @param maxDepthReached
 *            Maximum generation depth achieved
 * @param generationTimeMs
 *            Time taken for generation (milliseconds)
 * @param poolUsage
 *            Breakdown of pieces by pool
 * @param constraintViolations
 *            List of unmet constraints
 * @param attemptedConnections
 *            Number of connection attempts made
 * @param successfulConnections
 *            Number of successful placements
 */
public record GenerationStatistics(
		String structureId,
		int totalPieces,
		int maxDepthReached,
		long generationTimeMs,
		Map<String, Integer> poolUsage,
		List<String> constraintViolations,
		int attemptedConnections,
		int successfulConnections) {

	/**
	 * Gets the connection success rate as a percentage.
	 */
	public double getSuccessRate() {
		if (attemptedConnections == 0)
			return 0.0;
		return (successfulConnections * 100.0) / attemptedConnections;
	}

	/**
	 * Checks if generation was successful (no constraint violations).
	 */
	public boolean isSuccess() {
		return constraintViolations.isEmpty();
	}

	/**
	 * Formats a human-readable summary.
	 */
	public String getSummary() {
		return String.format(
				"Structure: %s\n" +
						"Pieces Placed: %d (max depth: %d)\n" +
						"Generation Time: %dms\n" +
						"Connection Success Rate: %.1f%% (%d/%d)\n" +
						"Constraint Violations: %d\n" +
						"Pool Usage: %s",
				structureId,
				totalPieces,
				maxDepthReached,
				generationTimeMs,
				getSuccessRate(),
				successfulConnections,
				attemptedConnections,
				constraintViolations.size(),
				poolUsage);
	}
}