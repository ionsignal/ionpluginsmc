package com.ionsignal.minecraft.iongenesis.generation.engine;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.seismic.type.vector.Vector3Int;

/**
 * Immutable key for caching jigsaw structure placements.
 * Uses chunk-aligned coordinates to ensure consistency across slight position variations.
 */
public record PlacementCacheKey(
        String structureId,
        String packId,
        int spawnChunkX,
        int spawnChunkZ,
        long worldSeed) {

    /**
     * Creates a PlacementCacheKey with validation.
     */
    public PlacementCacheKey {
        if (structureId == null || structureId.isEmpty()) {
            throw new IllegalArgumentException("Structure ID cannot be null or empty");
        }
        if (packId == null || packId.isEmpty()) {
            throw new IllegalArgumentException("Pack ID cannot be null or empty");
        }
    }

    /**
     * Factory method to create a cache key from a world location.
     * 
     * @param structureId
     *            The ID of the structure being generated
     * @param pack
     *            The config pack containing the structure
     * @param spawnLocation
     *            The world location where generation starts
     * @param worldSeed
     *            The world seed for deterministic generation
     * @return A new PlacementCacheKey
     */
    public static PlacementCacheKey from(
            String structureId,
            ConfigPack pack,
            Vector3Int spawnLocation,
            long worldSeed) {
        // Convert world coordinates to chunk coordinates
        int chunkX = spawnLocation.getX() >> 4;
        int chunkZ = spawnLocation.getZ() >> 4;

        return new PlacementCacheKey(
                structureId,
                pack.getRegistryKey().toString(),
                chunkX,
                chunkZ,
                worldSeed);
    }

    /**
     * Gets a deterministic seed for this specific structure placement.
     * 
     * @return A seed derived from the cache key components
     */
    public long getStructureSeed() {
        long hash = worldSeed;
        hash ^= (long) spawnChunkX;
        hash ^= ((long) spawnChunkZ) << 32;
        hash ^= structureId.hashCode();
        hash ^= packId.hashCode() << 16;
        return hash;
    }
}