package com.ionsignal.minecraft.ionnerrus.network.messages;

import java.util.List;

/**
 * Represents the JSON payload received from the web to spawn an agent.
 * Matches the structure previously defined in IonCore.
 */
public record SpawnAgentRequest(
        String name,
        String skin, // Simplified skin name/url if needed
        String skinTexture, // Base64 value
        String skinSignature, // Base64 signature
        int definitionId,
        SpawnLocationData location,
        String ownerUuid,
        List<String> authorizedUuids) {
    /**
     * Nested record for location data to handle the specific JSON structure.
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