package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ioncore.debug.DebugStateSnapshot;
import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ConstraintViolation;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.GenerationStatistics;
import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;

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
        UUID sessionId // Added for debug session tracking
) implements DebugStateSnapshot { // Implements DebugStateSnapshot
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
        // Note: The caller is responsible for passing a snapshot() of the registry if this is for
        // debugging, but we can't enforce deep copy here easily without changing the constructor signature
        // logic significantly. We rely on StructurePlanner to call registry.snapshot(). Auto-calculate
        // bounds if not provided
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

    // Constructor for backward compatibility (non-debug usage)
    public StructureBlueprint(String structureId, Vector3Int origin, List<PlacedJigsawPiece> pieces,
            ConnectionRegistry connectionRegistry, AABB totalBounds,
            List<ConstraintViolation> violations, GenerationStatistics statistics) {
        this(structureId, origin, pieces, connectionRegistry, totalBounds, violations, statistics, null);
    }

    @Override
    public String getDebugLabel() {
        return "Jigsaw Gen | Pieces: " + pieces.size() + " | Violations: " + violations.size();
    }
}