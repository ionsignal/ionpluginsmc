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
import java.util.UUID;

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

    @Override
    public String getDebugLabel() {
        return "Jigsaw Gen | Pieces: " + pieces.size() + " | Violations: " + violations.size();
    }
}