package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonEvent;

import com.fasterxml.jackson.annotation.*;

import java.util.UUID;

public record AgentStatePayload(
        @JsonProperty("ownerId") UUID ownerId,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("sessionId") UUID sessionId,
        @JsonProperty("type") String type,
        @JsonProperty("status") SessionStatus status,
        @JsonProperty("location") Location location) implements IonEvent {
}