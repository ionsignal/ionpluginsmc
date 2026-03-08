package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import com.fasterxml.jackson.annotation.*;

import java.util.UUID;

public record SystemPersonaKillPayload(
        @JsonProperty("type") String type,
        @JsonProperty("ownerId") UUID ownerId,
        @JsonProperty("sessionId") UUID sessionId) implements IonCommand {
}