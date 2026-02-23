package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;
import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.network.model.EventEnvelope;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.SessionStatus;
import com.ionsignal.minecraft.ionnerrus.network.model.IonEventType;
import com.ionsignal.minecraft.ionnerrus.network.model.Location;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerJoinPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerQuitPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.RequestSpawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.RequestDespawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.RequestPersonaListPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.entity.Player;
import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class PayloadFactory {
    private static JsonNode toJsonNode(Object pojo) {
        try {
            return NerrusObjectMapper.INSTANCE.valueToTree(pojo);
        } catch (IllegalArgumentException e) {
            IonCore.getInstance().getLogger().log(Level.SEVERE, "Failed to convert payload to JsonNode", e);
            throw new RuntimeException("Serialization Failed", e);
        }
    }

    public static Optional<EventEnvelope> createAgentStateEnvelope(@NotNull NerrusAgent agent, @Nullable SessionStatus forcedStatus) {
        Persona persona = agent.getPersona();
        UUID sessionId = persona.getSessionId();
        if (sessionId == null) {
            return Optional.empty();
        }
        UUID ownerId = persona.getOwnerId();
        Objects.requireNonNull(ownerId, "Agent ownerId cannot be null for state broadcasting");
        IdentityService identityService = IonCore.getInstance().getIdentityService();
        Optional<Optional<IonUser>> cachedIdentity = identityService.getCachedIdentity(ownerId);
        if (cachedIdentity.isEmpty() || cachedIdentity.get().isEmpty()) {
            return Optional.empty();
        }
        IonUser owner = cachedIdentity.get().get();
        SessionStatus status = forcedStatus;
        if (status == null) {
            status = SessionStatus.ACTIVE;
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
                toJsonNode(payload));
        return Optional.of(envelope);
    }

    public static EventEnvelope createRequestSpawnEnvelope(@NotNull IonUser owner, @NotNull UUID definitionId,
            @NotNull SpawnLocation location) {
        RequestSpawnPayload payload = new RequestSpawnPayload(
                owner,
                definitionId,
                IonEventType.REQUEST_PERSONA_SPAWN.getValue(),
                location);
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public static EventEnvelope createRequestDespawnEnvelope(@NotNull IonUser owner, @NotNull UUID definitionId, @NotNull UUID sessionId) {
        RequestDespawnPayload payload = new RequestDespawnPayload(
                owner,
                definitionId,
                sessionId,
                IonEventType.REQUEST_PERSONA_DESPAWN.getValue());
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public static EventEnvelope createRequestPersonaListEnvelope(@NotNull IonUser owner) {
        RequestPersonaListPayload payload = new RequestPersonaListPayload(
                owner,
                IonEventType.REQUEST_PERSONA_LIST.getValue());
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public static EventEnvelope createPlayerJoinEnvelope(@NotNull Player player) {
        MinecraftIdentity identity = createMinecraftIdentity(player);
        PlayerJoinPayload payload = new PlayerJoinPayload(
                IonEventType.EVENT_PLAYER_JOIN.getValue(),
                identity);
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public static EventEnvelope createPlayerQuitEnvelope(@NotNull Player player) {
        MinecraftIdentity identity = createMinecraftIdentity(player);
        PlayerQuitPayload payload = new PlayerQuitPayload(
                IonEventType.EVENT_PLAYER_QUIT.getValue(),
                identity);
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public static Location fromBukkitLocation(@NotNull org.bukkit.Location loc) {
        return new Location(
                loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch());
    }

    public static MinecraftIdentity createMinecraftIdentity(@NotNull Player player) {
        return new MinecraftIdentity(player.getUniqueId(), player.getName());
    }
}