package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.network.model.EventEnvelope;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatus;
import com.ionsignal.minecraft.ionnerrus.network.model.IonEventType;
import com.ionsignal.minecraft.ionnerrus.network.model.Location;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerIdentity;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Factory for creating network payloads from internal Nerrus objects.
 * Centralizes logic for converting Bukkit/Persona state into generated Network Models.
 */
public class PayloadFactory {
    /**
     * Creates an AgentStatePayload wrapped in an EventEnvelope.
     *
     * @param agent
     *            The agent to capture state from.
     * @param forcedStatus
     *            Optional status override (e.g., OFFLINE). If null, status is calculated based on
     *            movement.
     * @return An Optional containing the envelope, or empty if the agent lacks a Session ID.
     */
    public static Optional<EventEnvelope> createAgentStateEnvelope(@NotNull NerrusAgent agent, @Nullable AgentStatus forcedStatus) {
        Persona persona = agent.getPersona();
        UUID sessionId = persona.getSessionId();
        // Guard: Cannot report state for agents without a session (e.g. legacy/ad-hoc spawns)
        if (sessionId == null) {
            return Optional.empty();
        }
        // Determine Status
        AgentStatus status = forcedStatus;
        if (status == null) {
            // Calculate based on physical body state
            boolean isMoving = persona.isSpawned() && persona.getPhysicalBody().movement().isMoving();
            status = isMoving ? AgentStatus.WALKING : AgentStatus.IDLE;
        }
        // Resolve Location
        Location locationModel = fromBukkitLocation(persona.getLocation());
        // Resolve Owner Identity
        PlayerIdentity ownerIdentity = createIdentity(persona.getOwnerId());
        // Construct Payload
        AgentStatePayload payload = new AgentStatePayload(
                ownerIdentity,
                persona.getDefinitionId(),
                sessionId,
                IonEventType.EVENT_PERSONA_STATE.getValue(),
                agent.getName(),
                status,
                locationModel);
        // Wrap in Envelope
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                System.currentTimeMillis(),
                payload);
        return Optional.of(envelope);
    }

    /**
     * Converts a Bukkit Location to a Network Model Location.
     */
    public static Location fromBukkitLocation(@NotNull org.bukkit.Location loc) {
        return new Location(
                loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch());
    }

    /**
     * Creates a PlayerIdentity model from a UUID.
     * Handles offline player lookup safely.
     */
    public static PlayerIdentity createIdentity(@Nullable UUID uuid) {
        if (uuid == null) {
            return new PlayerIdentity(null, "System", null);
        }
        String username = "Unknown";
        org.bukkit.entity.Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null) {
            username = onlinePlayer.getName();
        } else {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            if (offlinePlayer.getName() != null) {
                username = offlinePlayer.getName();
            }
        }
        // Note: userId is passed as null here because Nerrus doesn't strictly know the Web User ID
        // without a DB lookup, and the Web side can infer it from the UUID if needed,
        // or we rely on the fact that this identity is mostly for display.
        return new PlayerIdentity(uuid, username, null);
    }
}