package com.ionsignal.minecraft.ionnerrus.terra.generation.placements;

import com.ionsignal.minecraft.ionnerrus.terra.generation.StructureBlueprint;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;

import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wrapper around StructureBlueprint to maintain compatibility with Caching and API.
 * Refactored to delegate to the Blueprint.
 */
public record JigsawPlacement(StructureBlueprint blueprint) {
    public List<PlacedJigsawPiece> pieces() {
        return blueprint.pieces();
    }

    public Vector3Int origin() {
        return blueprint.origin();
    }

    public String structureId() {
        return blueprint.structureId();
    }

    public AABB totalBounds() {
        return blueprint.totalBounds();
    }

    public ConnectionRegistry connectionRegistry() {
        return blueprint.connectionRegistry();
    }

    public static JigsawPlacement empty(Vector3Int origin, String structureId, ConnectionRegistry connectionRegistry) {
        return new JigsawPlacement(new StructureBlueprint(structureId, origin, null, connectionRegistry, null, null, null));
    }

    public boolean isEmpty() {
        return pieces().isEmpty();
    }

    public Stream<PlacedJigsawPiece> getPiecesInChunk(int chunkX, int chunkZ) {
        if (!totalBounds().intersectsChunkRegion(chunkX, chunkZ)) {
            return Stream.empty();
        }
        return pieces().stream().filter(piece -> piece.intersectsChunk(chunkX, chunkZ));
    }

    /**
     * Gets all pieces at a specific depth level.
     */
    public List<PlacedJigsawPiece> getPiecesAtDepth(int depth) {
        return pieces().stream()
                .filter(piece -> piece.depth() == depth)
                .toList();
    }

    /**
     * Gets the start piece (depth 0).
     */
    public PlacedJigsawPiece getStartPiece() {
        return pieces().stream()
                .filter(piece -> piece.depth() == 0)
                .findFirst()
                .orElse(null);
    }

    public int getPieceCount() {
        return pieces().size();
    }

    /**
     * Calculates the total number of blocks in this placement.
     */
    public int getTotalBlockCount() {
        return pieces().stream()
                .mapToInt(piece -> piece.structureData().blocks().size())
                .sum();
    }

    /**
     * Gets a breakdown of pool usage.
     * Delegates to GenerationStatistics if available.
     */
    public Map<String, Integer> getPoolUsageBreakdown() {
        if (blueprint.statistics() != null && blueprint.statistics().poolUsage() != null) {
            return blueprint.statistics().poolUsage();
        }
        // Fallback calculation if stats are missing
        return pieces().stream()
                .filter(piece -> piece.sourcePoolId() != null)
                .collect(Collectors.groupingBy(
                        PlacedJigsawPiece::sourcePoolId,
                        Collectors.summingInt(p -> 1)));
    }

    /**
     * Gets a breakdown by specific element file.
     */
    public Map<String, Integer> getElementUsageBreakdown() {
        return pieces().stream()
                .collect(Collectors.groupingBy(
                        PlacedJigsawPiece::structureId,
                        Collectors.summingInt(p -> 1)));
    }

    public String getSummary() {
        if (blueprint.statistics() != null) {
            return blueprint.statistics().getSummary();
        }
        return "JigsawPlacement[id=" + structureId() + ", pieces=" + getPieceCount() + "]";
    }
}