package com.ionsignal.minecraft.iongenesis.generation.logic;

import com.dfsek.seismic.type.vector.Vector3Int;
import com.ionsignal.minecraft.iongenesis.generation.oracle.TerrainOracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Performs topological analysis of the terrain ahead of a connection.
 * Uses a "Walker" algorithm to trace the surface across a grid (Kernel),
 * detecting slopes, cliffs, and walls.
 */
public class TerrainTopologist {
    @SuppressWarnings("unused")
    private static final Logger LOGGER = Logger.getLogger(TerrainTopologist.class.getName());

    // Configuration constants
    private static final int PROBE_DISTANCE = 3; // Distance from connection to grid center
    private static final int VERTICAL_SEARCH_LIMIT = 6; // Max steps up/down for walker
    private static final int GRID_RADIUS = 1; // 1 = 3x3 grid, 2 = 5x5 grid. Using 3x3 for performance.

    /**
     * Analyzes the terrain topology relative to a connection.
     *
     * @param connectionPos
     *            The world position of the connection.
     * @param orientation
     *            The orientation string of the connection (e.g., "north", "east_up").
     * @param oracle
     *            The terrain oracle to query.
     * @return A TopologyResult containing the context and the sampled grid points.
     */
    public TopologyResult analyze(Vector3Int connectionPos, String orientation, TerrainOracle oracle) {
        // Determine Forward Vector and Anchor Point
        String primaryDir = orientation.toLowerCase().split("_")[0];
        int dx = 0;
        int dz = 0;
        switch (primaryDir) {
            case "north" -> dz = -1;
            case "south" -> dz = 1;
            case "east" -> dx = 1;
            case "west" -> dx = -1;
        }
        // Anchor is 3 blocks ahead of the connection
        int anchorX = connectionPos.getX() + (dx * PROBE_DISTANCE);
        int anchorZ = connectionPos.getZ() + (dz * PROBE_DISTANCE);
        int startY = connectionPos.getY();
        // Sample the Anchor (Center of Grid)
        // We use the connection Y as the start hint.
        Optional<Integer> anchorSurfaceOpt = oracle.findSurface(anchorX, startY, anchorZ, VERTICAL_SEARCH_LIMIT);
        // Fail-Fast: If Anchor is invalid, we assume total obstruction or void.
        if (anchorSurfaceOpt.isEmpty()) {
            // Heuristic: If we can't find surface within limit, assume obstruction if looking up, or cliff if
            // looking down?
            // For safety, we treat "Unknown" as "Obstructed" to force a terminator.
            return new TopologyResult(
                    new TerrainContext(TerrainTrend.FLAT, startY, 0, 0, true, false),
                    List.of(Vector3Int.of(anchorX, startY, anchorZ)) // Return just the failed anchor
            );
        }
        int anchorY = anchorSurfaceOpt.get();
        List<Vector3Int> sampledPoints = new ArrayList<>();
        sampledPoints.add(Vector3Int.of(anchorX, anchorY, anchorZ));
        // Grid Sampling (Neighbor Propagation)
        // We iterate a grid around the anchor.
        // We use the ANCHOR'S Y as the hint for neighbors to follow the surface.
        int minDelta = Integer.MAX_VALUE;
        int maxDelta = Integer.MIN_VALUE;
        long totalY = 0;
        int sampleCount = 0;
        boolean obstructed = false;
        boolean cliff = false;
        // Grid offsets relative to Anchor, rotated to match orientation
        // For a 3x3 grid: x in [-1, 1], z in [-1, 1] relative to anchor
        for (int ox = -GRID_RADIUS; ox <= GRID_RADIUS; ox++) {
            for (int oz = -GRID_RADIUS; oz <= GRID_RADIUS; oz++) {
                // Skip center (already sampled)
                if (ox == 0 && oz == 0) {
                    int delta = anchorY - startY;
                    minDelta = Math.min(minDelta, delta);
                    maxDelta = Math.max(maxDelta, delta);
                    totalY += anchorY;
                    sampleCount++;
                    continue;
                }
                // Rotate offsets based on orientation to align grid with connection facing
                // If facing North (dz=-1), local X is World X, local Z is World Z (inverted?)
                // Actually, let's keep it simple: The grid is axis-aligned in world space centered on Anchor.
                // Since we want to sample the "patch" of terrain, axis alignment is fine regardless of rotation.
                int probeX = anchorX + ox;
                int probeZ = anchorZ + oz;
                // Walk from Anchor Y
                Optional<Integer> surfaceOpt = oracle.findSurface(probeX, anchorY, probeZ, VERTICAL_SEARCH_LIMIT);
                if (surfaceOpt.isPresent()) {
                    int surfaceY = surfaceOpt.get();
                    sampledPoints.add(Vector3Int.of(probeX, surfaceY, probeZ));

                    int delta = surfaceY - startY; // Delta relative to CONNECTION height
                    minDelta = Math.min(minDelta, delta);
                    maxDelta = Math.max(maxDelta, delta);
                    totalY += surfaceY;
                    sampleCount++;
                } else {
                    // If a neighbor fails, determine why. Since findSurface returns empty for BOTH obstruction and
                    // cliff (out of bounds), we need a slightly smarter heuristic. For now, if neighbor fails but
                    // anchor worked, it's likely a steep drop or wall. We'll flag as 'obstructed' to be safe.
                    obstructed = true;
                    // This visually snaps the failed probe to the search plane level, making cliffs/walls obvious.
                    sampledPoints.add(Vector3Int.of(probeX, anchorY, probeZ));
                }
            }
        }
        // Aggregation & Trend Analysis
        double averageY = (sampleCount > 0) ? (double) totalY / sampleCount : startY;
        // Refined Obstruction Logic:
        // If maxDelta is huge (> 3), it's a wall.
        if (maxDelta > 3)
            obstructed = true;
        // If minDelta is huge negative (< -4), it's a cliff.
        if (minDelta < -4)
            cliff = true;
        TerrainTrend trend = TerrainTrend.FLAT;
        if (!obstructed && !cliff) {
            double avgDelta = averageY - startY;
            if (avgDelta > 0.5) {
                trend = TerrainTrend.RISING;
            } else if (avgDelta < -0.5) {
                trend = TerrainTrend.FALLING;
            }
        }
        TerrainContext context = new TerrainContext(trend, averageY, minDelta, maxDelta, obstructed, cliff);
        return new TopologyResult(context, sampledPoints);
    }

    /**
     * Container for the analysis result and the raw grid points for visualization.
     */
    public record TopologyResult(TerrainContext context, List<Vector3Int> gridPoints) {
    }
}