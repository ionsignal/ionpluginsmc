package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.network.model.IonEvent;
import com.ionsignal.minecraft.ioncore.network.model.IonCommand;
import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;
import com.ionsignal.minecraft.ionnerrus.network.model.*;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

public class PayloadFactory {
    public PayloadFactory(JsonService jsonService) {
        // no-op
    }

    public IonCommand createCommandFailedEnvelope(
            @NotNull UUID ownerId,
            @NotNull String commandType,
            @NotNull String reason) {
        return new CommandFailedPayload(
                IonCommandType.SYSTEM_COMMAND_FAILED.getValue(),
                ownerId,
                reason,
                commandType);
    }

    public IonEvent createAgentStateEnvelope(
            @NotNull UUID sessionId,
            @NotNull UUID ownerId,
            @NotNull UUID definitionId,
            @NotNull Location locationModel,
            @Nullable SessionStatus forcedStatus) {
        Objects.requireNonNull(sessionId, "Agent sessionId cannot be null for state broadcasting");
        Objects.requireNonNull(ownerId, "Agent ownerId cannot be null for state broadcasting");
        Objects.requireNonNull(definitionId, "Agent definitionId cannot be null for state broadcasting");
        SessionStatus status = forcedStatus;
        if (status == null) {
            status = SessionStatus.ACTIVE;
        }
        return new AgentStatePayload(
                ownerId,
                definitionId,
                sessionId,
                IonEventType.EVENT_PERSONA_STATE.getValue(),
                status,
                locationModel);
    }

    public IonEvent createRequestSpawnEnvelope(
            @NotNull UUID ownerId,
            @NotNull UUID definitionId,
            @NotNull SpawnLocation location) {
        return new RequestSpawnPayload(
                ownerId,
                definitionId,
                IonEventType.REQUEST_PERSONA_SPAWN.getValue(),
                location);
    }

    public IonEvent createRequestDespawnEnvelope(
            @NotNull UUID ownerId,
            @NotNull UUID definitionId) {
        return new RequestDespawnPayload(
                ownerId,
                definitionId,
                IonEventType.REQUEST_PERSONA_DESPAWN.getValue());
    }

    public IonEvent createPlayerJoinEnvelope(
            @NotNull MinecraftIdentity identity) {
        return new PlayerJoinPayload(
                IonEventType.EVENT_PLAYER_JOIN.getValue(),
                identity);
    }

    public IonEvent createPlayerQuitEnvelope(
            @NotNull MinecraftIdentity identity) {
        return new PlayerQuitPayload(
                IonEventType.EVENT_PLAYER_QUIT.getValue(),
                identity);
    }
}