package com.ionsignal.minecraft.ionnerrus.network.schema;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Defines payloads received from the Web Dashboard (Ingress).
 */
public final class Incoming {
    private Incoming() {
    }

    /**
     * Represents the authoritative JSON blob stored in the 'persona_manifests' table.
     */
    public record AgentSyncPayload(
            String id,
            String name,

            @SerializedName("skin") Skin skin) {
        public record Skin(
                String type,

                @SerializedName(value = "value", alternate = {
                        "texture", "skinValue" }) String value,

                @SerializedName(value = "signature", alternate = { "skinSignature" }) String signature) {
        }
    }

    /**
     * Command to spawn a new Agent.
     */
    public record SpawnPayload(
            UUID definitionId,
            UUID ownerId,
            String name,
            SpawnLocation location) {
        public record SpawnLocation(
                String type, // "PLAYER" or "COORDINATES"
                @Nullable String playerName,
                @Nullable String world,
                @Nullable Double x,
                @Nullable Double y,
                @Nullable Double z) {
        }
    }

    /**
     * Command to despawn/remove an existing Agent.
     */
    public record DespawnPayload(
            UUID agentId) {
    }

    public record RefreshConfigPayload(
            UUID definitionId,
            @Nullable String instanceId,
            List<String> flags) {
    }
}