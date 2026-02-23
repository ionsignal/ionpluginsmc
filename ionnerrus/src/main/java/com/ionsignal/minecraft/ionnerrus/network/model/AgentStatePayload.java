package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonEvent;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import java.util.UUID;

import com.fasterxml.jackson.annotation.*;

public record AgentStatePayload(
        @JsonProperty("owner") IonUser owner,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("sessionId") UUID sessionId,
        @JsonProperty("type") String type,
        @JsonProperty("name") String name,
        @JsonProperty("status") SessionStatus status,
        @JsonProperty("location") Location location) implements IonEvent {

}