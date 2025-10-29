package com.ionsignal.minecraft.ionnerrus.terra.generation.debug;

import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.util.Rotation;
import com.ionsignal.minecraft.ionnerrus.terra.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;

/**
 * PHASE 3: NEW - Immutable snapshot of a generation step.
 * Used by branch undo feature to store complete state at each pause point.
 * 
 * This record captures everything needed to replay or branch from a step:
 * - The piece being tested
 * - The structure data
 * - Both rotation types separately
 * - Position and collision status
 * - Which file/pool was being tested
 */
public record DebugStep(
		int stepNumber,
		String phase,
		String info,
		PlacedJigsawPiece piece,
		NBTStructure.StructureData structure,
		Rotation geometricRotation,
		Rotation alignmentRotation,
		Vector3Int position,
		boolean hasCollision,
		String elementFile,
		String poolId,
		Vector3Int activeConnectionPoint) {

	/**
	 * PHASE 3: ADDED - Gets a human-readable description of this step.
	 */
	public String getDescription() {
		return String.format(
				"Step %d [%s]: %s (file=%s, geoRot=%s, alignRot=%s, pos=%s, collision=%s)",
				stepNumber,
				phase,
				info,
				elementFile != null ? elementFile : "unknown",
				geometricRotation,
				alignmentRotation,
				position,
				hasCollision);
	}
}