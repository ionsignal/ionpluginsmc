package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import com.fasterxml.jackson.annotation.*;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record SpawnPayload(
        @JsonProperty("ownerId") UUID ownerId,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("sessionId") UUID sessionId,
        @JsonProperty("type") String type,
        @JsonProperty("location") SpawnLocation location,
        @Nullable @JsonProperty("profile") PersonaProfile profile)
        implements IonCommand {
}