package com.ionsignal.minecraft.iongenesis.generation.tracking;

/**
 * Defines the lifecycle state of a jigsaw connection point.
 */
public enum ConnectionStatus {
    /**
     * The connection is available for attachment.
     */
    OPEN,

    /**
     * The connection has been successfully attached to another piece.
     * It should be replaced by its 'final_state' (usually air or water).
     */
    CONSUMED,

    /**
     * The connection was forced closed (e.g., by Panic Mode).
     * It should be replaced by a 'sealer_material' (e.g., cobblestone) to prevent open voids.
     */
    SEALED
}