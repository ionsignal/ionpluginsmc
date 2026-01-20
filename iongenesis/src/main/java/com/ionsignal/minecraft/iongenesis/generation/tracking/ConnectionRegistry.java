package com.ionsignal.minecraft.iongenesis.generation.tracking;

import com.dfsek.seismic.type.vector.Vector3Int;

import java.util.Map;
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
    private final Map<Vector3Int, ConnectionStatus> connectionStates;

    public ConnectionRegistry() {
        this.connectionStates = new ConcurrentHashMap<>();
    }

    private ConnectionRegistry(Map<Vector3Int, ConnectionStatus> data) {
        this.connectionStates = new ConcurrentHashMap<>(data);
    }

    /**
     * Marks a connection as consumed (connected to another piece).
     * 
     * @param position
     *            The world position of the connection point
     */
    public void markConsumed(Vector3Int position) {
        if (position == null)
            throw new IllegalArgumentException("Connection position cannot be null");
        connectionStates.put(position, ConnectionStatus.CONSUMED);
    }

    /**
     * Marks a connection as sealed (force-closed by Panic Mode).
     * 
     * @param position
     *            The world position of the connection point
     */
    public void markSealed(Vector3Int position) {
        if (position == null)
            throw new IllegalArgumentException("Connection position cannot be null");
        connectionStates.put(position, ConnectionStatus.SEALED);
    }

    /**
     * Checks if a connection has been consumed OR sealed.
     * Maintained for backward compatibility with StructurePlanner logic.
     * 
     * @param position
     *            The world position to check
     * @return true if the connection is not OPEN (either CONSUMED or SEALED)
     */
    public boolean isConsumed(Vector3Int position) {
        if (position == null)
            return false;
        ConnectionStatus status = connectionStates.get(position);
        return status == ConnectionStatus.CONSUMED || status == ConnectionStatus.SEALED;
    }

    /**
     * Gets the specific status of a connection.
     * 
     * @param position
     *            The world position to check
     * @return The status (OPEN if not present)
     */
    public ConnectionStatus getStatus(Vector3Int position) {
        if (position == null)
            return ConnectionStatus.OPEN;
        return connectionStates.getOrDefault(position, ConnectionStatus.OPEN);
    }

    /**
     * Gets the total number of consumed or sealed connections.
     * Useful for statistics and debugging.
     * 
     * @return The count of non-OPEN connections
     */
    public int getConsumedCount() {
        return connectionStates.size();
    }

    /**
     * Clears all consumed connection state.
     * Should only be used for testing or cleanup.
     */
    public void clear() {
        connectionStates.clear();
    }

    /**
     * Creates a deep copy of the current registry state.
     * Used for creating immutable snapshots for debugging.
     * 
     * @return A new independent ConnectionRegistry
     */
    public ConnectionRegistry snapshot() {
        return new ConnectionRegistry(this.connectionStates);
    }
}