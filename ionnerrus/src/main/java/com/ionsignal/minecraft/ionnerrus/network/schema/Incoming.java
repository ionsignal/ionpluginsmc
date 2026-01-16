package com.ionsignal.minecraft.ionnerrus.network.schema;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Defines payloads received from the Web Dashboard (Ingress).
 * These records represent commands or configuration data sent to the server.
 */
public final class Incoming {

    private Incoming() {}

    /**
     * Represents the authoritative JSON blob stored in the 'game_entity_sync' table.
     * This payload acts as the Read-Model for the agent's full configuration.
     */
    public record AgentSyncPayload(
        String id,
        String name,
        String premise, // Optional, mapped if present at root
        
        @SerializedName("skin")
        Skin skin,

        Behavior behavior,
        List<InventoryItem> inventory
    ) {
        public record Skin(
            String type, // "CustomSkin", "STEVE", etc.
            
            // Maps the DB "value" field to this component
            @SerializedName(value = "value", alternate = {"texture", "skinValue"})
            String value, 
            
            // Maps the DB "signature" field to this component
            @SerializedName(value = "signature", alternate = {"skinSignature"})
            String signature
        ) {}

        public record Behavior(
            Map<String, Object> traits, 
            List<String> rules
        ) {}
        
        public record InventoryItem(
            String material, 
            int amount
        ) {}
    }

    /**
     * Command to spawn a new Agent.
     * Handles the Union Type (Player vs Coordinates) via a "Fat Record" strategy.
     *
     * @param definitionId  The Web Identity UUID (volatile).
     * @param ownerId       The UUID of the user who owns this agent.
     * @param name          The display name of the agent.
     * @param location      The target spawn location strategy.
     */
    public record SpawnPayload(
        UUID definitionId,
        UUID ownerId,
        String name,
        SpawnLocation location
    ) {
        /**
         * Nested strategy for determining spawn location.
         * Corresponds to TS Union: { type: 'PLAYER', playerName: string } | { type: 'COORDINATES', ... }
         */
        public record SpawnLocation(
            String type, // "PLAYER" or "COORDINATES"
            @Nullable String playerName,
            @Nullable String world,
            @Nullable Double x,
            @Nullable Double y,
            @Nullable Double z
        ) {}
    }

    /**
     * Command to despawn/remove an existing Agent.
     *
     * @param agentId The UUID of the agent to remove.
     */
    public record DespawnPayload(
        UUID agentId
    ) {}

    /**
     * Signal to refresh an agent's configuration from the database.
     * 
     * @param definitionId The primary key of the Persona Definition.
     * @param instanceId   The specific agent instance ID (optional).
     * @param flags        Hints regarding what changed ("SKIN", "BEHAVIOR", "IDENTITY").
     */
    public record RefreshConfigPayload(
        UUID definitionId,
        @Nullable String instanceId,
        List<String> flags
    ) {}
}