package com.ionsignal.minecraft.iongenesis.generation;

import com.ionsignal.minecraft.ioncore.debug.DebugStateSnapshot;
import com.ionsignal.minecraft.iongenesis.generation.enforcement.ConstraintViolation;
import com.ionsignal.minecraft.iongenesis.generation.logic.TerrainTrend;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.iongenesis.generation.tracking.GenerationStatistics;
import com.ionsignal.minecraft.iongenesis.model.geometry.AABB;
import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Immutable representation of a generated structure plan. Contains the graph of pieces, their
 * positions, and generation metadata.
 */
public record StructureBlueprint(
        String structureId,
        Vector3Int origin,
        List<PlacedJigsawPiece> pieces,
        ConnectionRegistry connectionRegistry,
        AABB totalBounds,
        List<ConstraintViolation> violations,
        GenerationStatistics statistics,
        UUID sessionId,
        ProbeResult latestProbe) implements DebugStateSnapshot {

    // ProbeResult record to hold transient probe data
    public record ProbeResult(Vector3Int position, TerrainTrend trend) {
    }

    public StructureBlueprint {
        // Defensive copies for immutability
        if (pieces == null)
            pieces = Collections.emptyList();
        else
            pieces = List.copyOf(pieces); // Immutable copy
        if (violations == null)
            violations = Collections.emptyList();
        else
            violations = List.copyOf(violations); // Immutable copy
        if (connectionRegistry == null)
            connectionRegistry = new ConnectionRegistry();
        // Auto-calculate bounds if not provided
        if (totalBounds == null) {
            if (pieces.isEmpty()) {
                totalBounds = new AABB(origin, origin);
            } else {
                AABB bounds = pieces.get(0).getWorldBounds();
                for (int i = 1; i < pieces.size(); i++) {
                    bounds = bounds.expandToInclude(pieces.get(i).getWorldBounds());
                }
                totalBounds = bounds;
            }
        }
    }

    // Added compatibility constructor for 8 args (used by Planner previously)
    public StructureBlueprint(String structureId, Vector3Int origin, List<PlacedJigsawPiece> pieces,
            ConnectionRegistry connectionRegistry, AABB totalBounds,
            List<ConstraintViolation> violations, GenerationStatistics statistics,
            UUID sessionId) {
        this(structureId, origin, pieces, connectionRegistry, totalBounds, violations, statistics, sessionId, null);
    }

    // Constructor for backward compatibility (non-debug usage, 7 args)
    public StructureBlueprint(String structureId, Vector3Int origin, List<PlacedJigsawPiece> pieces,
            ConnectionRegistry connectionRegistry, AABB totalBounds,
            List<ConstraintViolation> violations, GenerationStatistics statistics) {
        this(structureId, origin, pieces, connectionRegistry, totalBounds, violations, statistics, null, null);
    }

    /**
     * Creates an empty blueprint. Used for fallbacks and simple placements.
     */
    public static StructureBlueprint empty(Vector3Int origin, String structureId, ConnectionRegistry connectionRegistry) {
        // Pass null for lists/stats; compact constructor handles initialization and bounds calculation.
        return new StructureBlueprint(structureId, origin, null, connectionRegistry, null, null, null, null, null);
    }

    @Override
    public String getDebugLabel() {
        return "Jigsaw Gen | Pieces: " + pieces.size() + " | Violations: " + violations.size();
    }

    public boolean isEmpty() {
        return pieces.isEmpty();
    }

    public Stream<PlacedJigsawPiece> getPiecesInChunk(int chunkX, int chunkZ) {
        if (!totalBounds.intersectsChunkRegion(chunkX, chunkZ)) {
            return Stream.empty();
        }
        return pieces.stream().filter(piece -> piece.intersectsChunk(chunkX, chunkZ));
    }

    /**
     * Gets all pieces at a specific depth level.
     */
    public List<PlacedJigsawPiece> getPiecesAtDepth(int depth) {
        return pieces.stream()
                .filter(piece -> piece.depth() == depth)
                .toList();
    }

    /**
     * Gets the start piece (depth 0).
     */
    public PlacedJigsawPiece getStartPiece() {
        return pieces.stream()
                .filter(piece -> piece.depth() == 0)
                .findFirst()
                .orElse(null);
    }

    /**
     * Calculates the total number of blocks in this placement.
     */
    public int getTotalBlockCount() {
        return pieces.stream()
                .mapToInt(piece -> piece.structureData().blocks().size())
                .sum();
    }

    /**
     * Gets a breakdown of pool usage.
     * Delegates to GenerationStatistics if available, otherwise calculates on the fly.
     */
    public Map<String, Integer> getPoolUsageBreakdown() {
        if (statistics != null && statistics.poolUsage() != null) {
            return statistics.poolUsage();
        }
        // Fallback calculation if stats are missing
        return pieces.stream()
                .filter(piece -> piece.sourcePoolId() != null)
                .collect(Collectors.groupingBy(
                        PlacedJigsawPiece::sourcePoolId,
                        Collectors.summingInt(p -> 1)));
    }

    /**
     * Gets a breakdown by specific element file.
     */
    public Map<String, Integer> getElementUsageBreakdown() {
        return pieces.stream()
                .collect(Collectors.groupingBy(
                        PlacedJigsawPiece::structureId,
                        Collectors.summingInt(p -> 1)));
    }

    public String getSummary() {
        if (statistics != null) {
            return statistics.getSummary();
        }
        return "StructureBlueprint[id=" + structureId + ", pieces=" + pieces.size() + "]";
    }
}