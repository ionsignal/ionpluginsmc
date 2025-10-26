package com.ionsignal.minecraft.ionnerrus.terra.util;

import com.dfsek.terra.api.util.vector.Vector3Int;
import com.dfsek.terra.api.util.Rotation;

/**
 * Axis-Aligned Bounding Box for spatial calculations and collision detection.
 * This immutable record represents a 3D rectangular region in world space.
 */
public record AABB(Vector3Int min, Vector3Int max) {
    
    /**
     * Creates an AABB with validation to ensure min <= max on all axes.
     */
    public AABB {
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            throw new IllegalArgumentException(
                String.format("Invalid AABB: min %s must be <= max %s", min, max)
            );
        }
    }
    
    /**
     * Checks if this AABB intersects with another AABB.
     * Two AABBs intersect if they overlap on all three axes.
     * 
     * @param other The other AABB to check intersection with
     * @return true if the AABBs intersect, false otherwise
     */
    public boolean intersects(AABB other) {
        return !(this.max.getX() < other.min.getX() || 
                 this.min.getX() > other.max.getX() ||
                 this.max.getY() < other.min.getY() || 
                 this.min.getY() > other.max.getY() ||
                 this.max.getZ() < other.min.getZ() || 
                 this.min.getZ() > other.max.getZ());
    }
    
    /**
     * Checks if this AABB intersects with a chunk column.
     * Assumes standard Minecraft 16x16 chunks with full world height.
     * 
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return true if this AABB intersects the chunk column
     */
    public boolean intersectsChunkRegion(int chunkX, int chunkZ) {
        // Calculate chunk boundaries in world coordinates
        int chunkMinX = chunkX << 4;  // chunkX * 16
        int chunkMaxX = (chunkX << 4) + 15;  // (chunkX * 16) + 15
        int chunkMinZ = chunkZ << 4;  // chunkZ * 16
        int chunkMaxZ = (chunkZ << 4) + 15;  // (chunkZ * 16) + 15
        
        // Create a chunk AABB with full Y range
        AABB chunkAABB = new AABB(
            Vector3Int.of(chunkMinX, Integer.MIN_VALUE, chunkMinZ),
            Vector3Int.of(chunkMaxX, Integer.MAX_VALUE, chunkMaxZ)
        );
        
        return this.intersects(chunkAABB);
    }
    
    /**
     * Creates an AABB for a structure piece at a given position with rotation.
     * The AABB accounts for how rotation affects the structure's dimensions.
     * 
     * @param position The world position of the structure's origin (typically its minimum corner when unrotated)
     * @param size The size of the structure in its local/unrotated space
     * @param rotation The rotation applied to the structure
     * @return An AABB encompassing the rotated structure
     */
    public static AABB fromPiece(Vector3Int position, Vector3Int size, Rotation rotation) {
        // Calculate the effective size after rotation
        Vector3Int rotatedSize = rotateSize(size, rotation);
        
        // The max is position + rotated size - 1 (since size is inclusive)
        Vector3Int max = Vector3Int.of(
            position.getX() + rotatedSize.getX() - 1,
            position.getY() + rotatedSize.getY() - 1,
            position.getZ() + rotatedSize.getZ() - 1
        );
        
        return new AABB(position, max);
    }
    
    /**
     * Rotates a size vector according to the given rotation.
     * Only X and Z dimensions are swapped for 90/270 degree rotations.
     * Y dimension is never affected by horizontal rotations.
     * 
     * @param size The original size vector
     * @param rotation The rotation to apply
     * @return The size vector after rotation
     */
    private static Vector3Int rotateSize(Vector3Int size, Rotation rotation) {
        return switch (rotation) {
            case CW_90, CCW_90 -> Vector3Int.of(size.getZ(), size.getY(), size.getX());
            case CW_180, NONE -> size;
        };
    }
    
    /**
     * Checks if this AABB contains a point.
     * 
     * @param point The point to check
     * @return true if the point is inside this AABB (inclusive)
     */
    public boolean contains(Vector3Int point) {
        return point.getX() >= min.getX() && point.getX() <= max.getX() &&
               point.getY() >= min.getY() && point.getY() <= max.getY() &&
               point.getZ() >= min.getZ() && point.getZ() <= max.getZ();
    }
    
    /**
     * Expands this AABB to include another AABB.
     * 
     * @param other The AABB to include
     * @return A new AABB that encompasses both this and the other AABB
     */
    public AABB expandToInclude(AABB other) {
        return new AABB(
            Vector3Int.of(
                Math.min(this.min.getX(), other.min.getX()),
                Math.min(this.min.getY(), other.min.getY()),
                Math.min(this.min.getZ(), other.min.getZ())
            ),
            Vector3Int.of(
                Math.max(this.max.getX(), other.max.getX()),
                Math.max(this.max.getY(), other.max.getY()),
                Math.max(this.max.getZ(), other.max.getZ())
            )
        );
    }
    
    /**
     * Gets the center point of this AABB.
     * 
     * @return The center point (may have fractional values, so returns the floor)
     */
    public Vector3Int getCenter() {
        return Vector3Int.of(
            (min.getX() + max.getX()) / 2,
            (min.getY() + max.getY()) / 2,
            (min.getZ() + max.getZ()) / 2
        );
    }
    
    /**
     * Gets the size of this AABB.
     * 
     * @return The dimensions of this AABB
     */
    public Vector3Int getSize() {
        return Vector3Int.of(
            max.getX() - min.getX() + 1,
            max.getY() - min.getY() + 1,
            max.getZ() - min.getZ() + 1
        );
    }
}