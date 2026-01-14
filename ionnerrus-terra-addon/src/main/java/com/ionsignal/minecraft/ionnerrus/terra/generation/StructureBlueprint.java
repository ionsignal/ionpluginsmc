package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement.ConstraintViolation;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.ConnectionRegistry;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.GenerationStatistics;
import com.ionsignal.minecraft.ionnerrus.terra.util.AABB;

import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.Collections;
import java.util.List;

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
        GenerationStatistics statistics) {
    public StructureBlueprint {
        if (pieces == null)
            pieces = Collections.emptyList();
        if (violations == null)
            violations = Collections.emptyList();
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
}