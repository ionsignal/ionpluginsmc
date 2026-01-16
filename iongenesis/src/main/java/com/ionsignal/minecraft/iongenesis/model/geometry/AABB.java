package com.ionsignal.minecraft.iongenesis.model.geometry;

import com.dfsek.seismic.type.Rotation;
import com.dfsek.seismic.type.vector.Vector3Int;
import com.ionsignal.minecraft.iongenesis.util.SpatialMath;

import java.util.Objects;

/**
 * Axis-Aligned Bounding Box for spatial calculations and collision detection.
 * This immutable record represents a 3D rectangular region in world space.
 */

public final class AABB {
    private final Vector3Int min;
    private final Vector3Int max;

    /**
     * Creates an AABB with validation to ensure min <= max on all axes.
     */
    public AABB(Vector3Int min, Vector3Int max) {
        this.min = min;
        this.max = max;
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            throw new IllegalArgumentException(
                    String.format("Invalid AABB: min %s must be <= max %s", min, max));
        }
    }

    /**
     * Return min
     */
    public Vector3Int min() {
        return min;
    }

    /**
     * Return max
     */
    public Vector3Int max() {
        return max;
    }

    /**
     * Checks if this AABB intersects with another AABB.
     * Two AABBs intersect if they overlap on all three axes.
     *
     * @param other
     *            The other AABB to check intersection with
     */
    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (other == null || getClass() != other.getClass())
            return false;
        AABB aabb = (AABB) other;
        // Use Vector3 for value-based equality.
        return min.toFloat().equals(aabb.min.toFloat()) && max.toFloat().equals(aabb.max.toFloat());
    }

    /**
     * Override the hashCode generation to that of Vector3
     */
    @Override
    public int hashCode() {
        // Use Vector3 for value-based hashing.
        return Objects.hash(min.toFloat(), max.toFloat());
    }

    /**
     * Checks if this AABB intersects with another AABB.
     * Two AABBs intersect if they overlap on all three axes.
     * 
     * @param other
     *            The other AABB to check intersection with
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
     * @param chunkX
     *            The chunk X coordinate
     * @param chunkZ
     *            The chunk Z coordinate
     * @return true if this AABB intersects the chunk column
     */
    public boolean intersectsChunkRegion(int chunkX, int chunkZ) {
        // Calculate chunk boundaries in world coordinates
        int chunkMinX = chunkX << 4; // chunkX * 16
        int chunkMaxX = (chunkX << 4) + 15; // (chunkX * 16) + 15
        int chunkMinZ = chunkZ << 4; // chunkZ * 16
        int chunkMaxZ = (chunkZ << 4) + 15; // (chunkZ * 16) + 15
        // Create a chunk AABB with full Y range
        AABB chunkAABB = new AABB(
                Vector3Int.of(chunkMinX, Integer.MIN_VALUE, chunkMinZ),
                Vector3Int.of(chunkMaxX, Integer.MAX_VALUE, chunkMaxZ));
        return this.intersects(chunkAABB);
    }

    /**
     * Creates an AABB for a structure piece at a given position with rotation.
     * This correctly accounts for how rotation affects the bounding box by:
     * 
     * Algorithm:
     * 1. Generate all 8 corners of the unrotated bounding box in structure-local space
     * 2. Rotate each corner using SpatialMath.rotate() (single source of truth)
     * 3. Transform each rotated corner to world space by adding the structure's world position
     * 4. Find the min/max coordinates of all 8 transformed corners
     * 
     * @param position
     *            The world position of the structure's origin (pivot point for rotation)
     * @param size
     *            The size of the structure in its local/unrotated space
     * @param rotation
     *            The rotation applied to the structure
     * @return An AABB encompassing the rotated structure in world space
     */
    public static AABB fromPiece(Vector3Int position, Vector3Int size, Rotation rotation) {
        // Special case: no rotation - simple calculation
        if (rotation == Rotation.NONE) {
            Vector3Int max = Vector3Int.of(
                    position.getX() + size.getX() - 1,
                    position.getY() + size.getY() - 1,
                    position.getZ() + size.getZ() - 1);
            return new AABB(position, max);
        }
        // For rotated pieces, we must compute all 8 corners of the bounding box
        // and find their extents after rotation.
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        // Generate and process all 8 corners of the unrotated bounding box
        // Corner indices: 000, 001, 010, 011, 100, 101, 110, 111 (binary: XYZ)
        for (int cornerIndex = 0; cornerIndex < 8; cornerIndex++) {
            // Decode corner index into coordinates (0 or size-1 for each axis)
            int localX = ((cornerIndex & 0b100) != 0) ? size.getX() - 1 : 0;
            int localY = ((cornerIndex & 0b010) != 0) ? size.getY() - 1 : 0;
            int localZ = ((cornerIndex & 0b001) != 0) ? size.getZ() - 1 : 0;
            // Create a position vector for this corner in structure-local space
            Vector3Int localCorner = Vector3Int.of(localX, localY, localZ);
            // Rotate using the SAME logic as block placement
            // This is the critical fix - we now use SpatialMath as the single source of truth
            Vector3Int rotatedCorner = SpatialMath.rotate(localCorner, rotation, size);
            // Transform the rotated corner to world space
            int worldX = position.getX() + rotatedCorner.getX();
            int worldY = position.getY() + rotatedCorner.getY();
            int worldZ = position.getZ() + rotatedCorner.getZ();
            // Update bounds
            minX = Math.min(minX, worldX);
            maxX = Math.max(maxX, worldX);
            minY = Math.min(minY, worldY);
            maxY = Math.max(maxY, worldY);
            minZ = Math.min(minZ, worldZ);
            maxZ = Math.max(maxZ, worldZ);
        }
        return new AABB(
                Vector3Int.of(minX, minY, minZ),
                Vector3Int.of(maxX, maxY, maxZ));
    }

    /**
     * Checks if this AABB contains a point.
     * 
     * @param point
     *            The point to check
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
     * @param other
     *            The AABB to include
     * @return A new AABB that encompasses both this and the other AABB
     */
    public AABB expandToInclude(AABB other) {
        return new AABB(
                Vector3Int.of(
                        Math.min(this.min.getX(), other.min.getX()),
                        Math.min(this.min.getY(), other.min.getY()),
                        Math.min(this.min.getZ(), other.min.getZ())),
                Vector3Int.of(
                        Math.max(this.max.getX(), other.max.getX()),
                        Math.max(this.max.getY(), other.max.getY()),
                        Math.max(this.max.getZ(), other.max.getZ())));
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
                (min.getZ() + max.getZ()) / 2);
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
                max.getZ() - min.getZ() + 1);
    }
}