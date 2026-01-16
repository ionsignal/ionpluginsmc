package com.ionsignal.minecraft.iongenesis.generation.placements;

import com.dfsek.seismic.type.vector.Vector3Int;
import com.dfsek.seismic.type.Rotation;

import com.ionsignal.minecraft.iongenesis.model.NBTStructure;
import com.ionsignal.minecraft.iongenesis.util.AABB;

import java.util.List;

/**
 * Represents a single structure piece that has been placed in the world during jigsaw generation.
 * This immutable record contains all information needed to render the piece into the world.
 * 
 * Connection consumption state is tracked externally via ConnectionRegistry, ensuring true
 * immutability.
 *
 * @param nbtFile
 *            The path to the NBT file containing the structure data
 * @param worldPosition
 *            The absolute world position where this piece's origin is placed
 * @param rotation
 *            The rotation applied to this piece
 * @param structureData
 *            The parsed NBT structure data
 * @param connections
 *            The transformed jigsaw connection points in world space
 * @param depth
 *            The generation depth (0 for start piece, increases with each connection)
 * @param parent
 *            The piece that spawned this one (null for start piece)
 * @param sourcePoolId
 *            The ID of the pool this piece was selected from
 */
public record PlacedJigsawPiece(
        String structureId,
        Vector3Int worldPosition,
        Rotation rotation,
        NBTStructure.StructureData structureData,
        List<TransformedJigsawBlock> connections,
        int depth,
        PlacedJigsawPiece parent,
        String sourcePoolId) {

    /**
     * Creates a PlacedJigsawPiece with validation.
     */
    public PlacedJigsawPiece {
        if (structureId == null || structureId.isEmpty()) {
            throw new IllegalArgumentException("Structure ID cannot be null or empty");
        }
        if (worldPosition == null) {
            throw new IllegalArgumentException("World position cannot be null");
        }
        if (rotation == null) {
            rotation = Rotation.NONE;
        }
        if (structureData == null) {
            throw new IllegalArgumentException("Structure data cannot be null");
        }
        if (connections == null) {
            throw new IllegalArgumentException("Connections list cannot be null");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("Depth cannot be negative");
        }
    }

    /**
     * Gets the world-space bounding box of this placed piece.
     * This accounts for the piece's position and rotation.
     * 
     * @return The AABB encompassing this structure piece in world coordinates
     */
    public AABB getWorldBounds() {
        return AABB.fromPiece(worldPosition, structureData.size(), rotation);
    }

    /**
     * Checks if this piece intersects with a chunk region.
     * 
     * @param chunkX
     *            The chunk X coordinate
     * @param chunkZ
     *            The chunk Z coordinate
     * @return true if this piece overlaps the chunk
     */
    public boolean intersectsChunk(int chunkX, int chunkZ) {
        return getWorldBounds().intersectsChunkRegion(chunkX, chunkZ);
    }

    /**
     * Finds a connection by its world position.
     * 
     * @param position
     *            The world position to search for
     * @return The connection at that position, or null if not found
     */
    public TransformedJigsawBlock findConnectionAt(Vector3Int position) {
        return connections.stream()
                .filter(conn -> conn.position().toFloat().equals(position.toFloat()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Factory method to create a start piece (no parent).
     */
    public static PlacedJigsawPiece createStartPiece(
            String structureId, // Was nbtFile
            Vector3Int worldPosition,
            Rotation rotation,
            NBTStructure.StructureData structureData,
            List<TransformedJigsawBlock> connections,
            String sourcePoolId) {
        return new PlacedJigsawPiece(
                structureId,
                worldPosition,
                rotation,
                structureData,
                connections,
                0,
                null,
                sourcePoolId);
    }
}