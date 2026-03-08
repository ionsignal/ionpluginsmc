package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonEvent;

import com.fasterxml.jackson.annotation.*;

import java.util.UUID;

public record RequestSpawnPayload(
        @JsonProperty("ownerId") UUID ownerId,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("type") String type,
        @JsonProperty("location") SpawnLocation location) implements IonEvent {
}