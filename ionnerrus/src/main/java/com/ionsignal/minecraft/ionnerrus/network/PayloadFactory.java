package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;
import com.ionsignal.minecraft.ioncore.network.model.EventEnvelope;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.CommandFailedPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.IonCommandType;
import com.ionsignal.minecraft.ionnerrus.network.model.SessionStatus;
import com.ionsignal.minecraft.ionnerrus.network.model.IonEventType;
import com.ionsignal.minecraft.ionnerrus.network.model.Location;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerJoinPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerQuitPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.RequestSpawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.RequestDespawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.RequestPersonaListPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;

import com.fasterxml.jackson.databind.JsonNode;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class PayloadFactory {

    private final JsonService jsonService;
    private final IdentityService identityService;

    public PayloadFactory(JsonService jsonService, IdentityService identityService) {
        this.jsonService = jsonService;
        this.identityService = identityService;
    }

    private JsonNode toJsonNode(Object pojo) {
        return jsonService.valueToTree(pojo);
    }

    public EventEnvelope createCommandFailedEnvelope(
            @NotNull IonUser owner,
            @NotNull String commandType,
            @NotNull String reason) {
        CommandFailedPayload payload = new CommandFailedPayload(
                owner,
                reason,
                commandType,
                IonCommandType.SYSTEM_COMMAND_FAILED.getValue());
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public EventEnvelope createAgentStateEnvelope(
            @NotNull UUID sessionId,
            @NotNull UUID ownerId,
            @NotNull UUID definitionId,
            @NotNull String agentName,
            @NotNull Location locationModel,
            @Nullable SessionStatus forcedStatus) {
        Objects.requireNonNull(sessionId, "Agent sessionId cannot be null for state broadcasting");
        Objects.requireNonNull(ownerId, "Agent ownerId cannot be null for state broadcasting");
        Objects.requireNonNull(definitionId, "Agent definitionId cannot be null for state broadcasting");
        Optional<Optional<IonUser>> cachedIdentity = identityService.getCachedIdentity(ownerId);
        if (cachedIdentity.isEmpty() || cachedIdentity.get().isEmpty()) {
            throw new IllegalStateException("Cannot create state envelope: Owner identity not found in cache for UUID " + ownerId);
        }
        IonUser owner = cachedIdentity.get().get();
        SessionStatus status = forcedStatus;
        if (status == null) {
            status = SessionStatus.ACTIVE;
        }
        AgentStatePayload payload = new AgentStatePayload(
                owner,
                definitionId,
                sessionId,
                IonEventType.EVENT_PERSONA_STATE.getValue(),
                agentName,
                status,
                locationModel);
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                System.currentTimeMillis(),
                toJsonNode(payload));
        return envelope;
    }

    public EventEnvelope createRequestSpawnEnvelope(
            @NotNull IonUser owner,
            @NotNull UUID definitionId,
            @NotNull SpawnLocation location) {
        RequestSpawnPayload payload = new RequestSpawnPayload(
                owner,
                definitionId,
                IonEventType.REQUEST_PERSONA_SPAWN.getValue(),
                location);
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public EventEnvelope createRequestDespawnEnvelope(
            @NotNull IonUser owner,
            @NotNull UUID definitionId) {
        RequestDespawnPayload payload = new RequestDespawnPayload(
                owner,
                definitionId,
                IonEventType.REQUEST_PERSONA_DESPAWN.getValue());
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public EventEnvelope createRequestPersonaListEnvelope(
            @NotNull IonUser owner) {
        RequestPersonaListPayload payload = new RequestPersonaListPayload(
                owner,
                IonEventType.REQUEST_PERSONA_LIST.getValue());
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public EventEnvelope createPlayerJoinEnvelope(
            @NotNull MinecraftIdentity identity) {
        PlayerJoinPayload payload = new PlayerJoinPayload(
                IonEventType.EVENT_PLAYER_JOIN.getValue(),
                identity);
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }

    public EventEnvelope createPlayerQuitEnvelope(
            @NotNull MinecraftIdentity identity) {
        PlayerQuitPayload payload = new PlayerQuitPayload(
                IonEventType.EVENT_PLAYER_QUIT.getValue(),
                identity);
        return new EventEnvelope(UUID.randomUUID(), System.currentTimeMillis(), toJsonNode(payload));
    }
}