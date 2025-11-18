package com.ionsignal.minecraft.ionnerrus.terra.generation.tracking;

import com.dfsek.terra.api.util.vector.Vector3;
import com.dfsek.terra.api.util.vector.Vector3Int;

import java.util.Map;
// import java.util.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for tracking which jigsaw connections have been consumed during structure
 * generation. This provides the single source of truth for connection state, eliminating the need
 * to mutate immutable PlacedJigsawPiece records.
 * 
 * Key benefits:
 * - O(1) lookup performance
 * - No stale reference issues
 * - True immutability for pieces
 * - Clear separation between structural data (pieces) and lifecycle state (registry)
 * 
 * Thread Safety: Uses ConcurrentHashMap for thread-safe access during parallel chunk generation.
 */
public class ConnectionRegistry {
    // private static final Logger LOGGER = Logger.getLogger(ConnectionRegistry.class.getName());
    private final Map<Vector3, Boolean> consumedConnections;

    public ConnectionRegistry() {
        this.consumedConnections = new ConcurrentHashMap<>();
    }

    /**
     * Marks a connection as consumed (connected to another piece).
     * 
     * @param position
     *            The world position of the connection point
     */
    public void markConsumed(Vector3Int position) {
        if (position == null) {
            throw new IllegalArgumentException("Connection position cannot be null");
        }
        consumedConnections.put(position.toVector3(), Boolean.TRUE);
    }

    /**
     * Checks if a connection has been consumed.
     * 
     * @param position
     *            The world position to check
     * @return true if the connection has been consumed, false otherwise
     */
    public boolean isConsumed(Vector3Int position) {
        if (position == null) {
            return false;
        }
        return consumedConnections.containsKey(position.toVector3());
    }

    /**
     * Gets the total number of consumed connections.
     * Useful for statistics and debugging.
     * 
     * @return The count of consumed connections
     */
    public int getConsumedCount() {
        return consumedConnections.size();
    }

    /**
     * Clears all consumed connection state.
     * Should only be used for testing or cleanup.
     */
    public void clear() {
        consumedConnections.clear();
    }
}