package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;

/**
 * Observer interface for the StructurePlanner.
 * Used for debugging and visualization (Time-Slicing).
 */
public interface PlanningEventListener {
    void onPiecePlaced(PlacedJigsawPiece piece);

    void onConnectionFailed(PlacedJigsawPiece parent, String reason);

    void onGenerationFinished(StructureBlueprint blueprint);
}