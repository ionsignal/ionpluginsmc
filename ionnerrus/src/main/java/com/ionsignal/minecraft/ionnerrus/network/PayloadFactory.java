package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;
import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.network.model.EventEnvelope;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatus;
import com.ionsignal.minecraft.ionnerrus.network.model.IonEventType;
import com.ionsignal.minecraft.ionnerrus.network.model.Location;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerJoinPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerQuitPayload;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Factory for creating network payloads from internal Nerrus objects.
 * Centralizes logic for converting Bukkit/Persona state into generated Network Models.
 */
public class PayloadFactory {
    /**
     * Helper to serialize POJO to JSON String using standard mapper.
     */
    private static String toJsonString(Object pojo) {
        try {
            return NerrusObjectMapper.INSTANCE.writeValueAsString(pojo);
        } catch (Exception e) {
            IonCore.getInstance().getLogger().log(Level.SEVERE, "Failed to serialize payload to JSON String", e);
            throw new RuntimeException("Serialization Failed", e);
        }
    }

    /**
     * Creates an AgentStatePayload wrapped in an EventEnvelope.
     *
     * @param agent
     *            The agent to capture state from.
     * @param forcedStatus
     *            Optional status override (e.g., OFFLINE). If null, status is calculated based on
     *            movement.
     * @return An Optional containing the envelope, or empty if the agent lacks a Session ID or Owner
     *         Identity.
     */
    public static Optional<EventEnvelope> createAgentStateEnvelope(@NotNull NerrusAgent agent, @Nullable AgentStatus forcedStatus) {
        Persona persona = agent.getPersona();
        UUID sessionId = persona.getSessionId();
        // Guard: Cannot report state for agents without a session (e.g. legacy/ad-hoc spawns)
        if (sessionId == null) {
            return Optional.empty();
        }
        // We need the full IonUser object to satisfy the schema.
        UUID ownerId = persona.getOwnerId();
        if (ownerId == null) {
            return Optional.empty(); // No owner, cannot form payload
        }
        // Access IdentityService to get cached IonUser
        IdentityService identityService = IonCore.getInstance().getIdentityService();
        Optional<Optional<IonUser>> cachedIdentity = identityService.getCachedIdentity(ownerId);
        // If cache is missing or user is unlinked (empty inner optional), we cannot send the event.
        if (cachedIdentity.isEmpty() || cachedIdentity.get().isEmpty()) {
            return Optional.empty();
        }
        IonUser owner = cachedIdentity.get().get();
        AgentStatus status = forcedStatus;
        if (status == null) {
            boolean isMoving = persona.isSpawned() && persona.getPhysicalBody().movement().isMoving();
            status = isMoving ? AgentStatus.WALKING : AgentStatus.IDLE;
        }
        Location locationModel = fromBukkitLocation(persona.getLocation());
        AgentStatePayload payload = new AgentStatePayload(
                owner,
                persona.getDefinitionId(),
                sessionId,
                IonEventType.EVENT_PERSONA_STATE.getValue(),
                agent.getName(),
                status,
                locationModel);
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                System.currentTimeMillis(),
                toJsonString(payload));
        return Optional.of(envelope);
    }

    /**
     * Creates a PlayerJoinPayload wrapped in an EventEnvelope.
     */
    public static EventEnvelope createPlayerJoinEnvelope(@NotNull Player player) {
        MinecraftIdentity identity = createMinecraftIdentity(player);
        PlayerJoinPayload payload = new PlayerJoinPayload(
                IonEventType.EVENT_PLAYER_JOIN.getValue(),
                identity);
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonString(payload));
    }

    /**
     * Creates a PlayerQuitPayload wrapped in an EventEnvelope.
     */
    public static EventEnvelope createPlayerQuitEnvelope(@NotNull Player player) {
        MinecraftIdentity identity = createMinecraftIdentity(player);
        PlayerQuitPayload payload = new PlayerQuitPayload(
                IonEventType.EVENT_PLAYER_QUIT.getValue(),
                identity);
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonString(payload));
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
     * Creates a simple MinecraftIdentity from a Bukkit Player.
     */
    public static MinecraftIdentity createMinecraftIdentity(@NotNull Player player) {
        return new MinecraftIdentity(player.getUniqueId(), player.getName());
    }
}