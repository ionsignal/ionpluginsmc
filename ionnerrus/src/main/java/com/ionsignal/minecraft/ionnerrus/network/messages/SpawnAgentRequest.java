package com.ionsignal.minecraft.ionnerrus.network.messages;

/**
 * Represents the JSON payload received from the web to spawn an agent.
 * Updated to match the new JSON structure where IDs are UUID strings and skin data is removed.
 */
public record SpawnAgentRequest(
        String definitionId, // UUID String
        String ownerId,      // UUID String
        String name,
        SpawnLocationData location
) {
    /**
     * Nested record for location data.
     */
    public record SpawnLocationData(
            String type, // "PLAYER" or "COORDINATES"
            String playerName,
            double x,
            double y,
            double z,
            String world) {
    }
}