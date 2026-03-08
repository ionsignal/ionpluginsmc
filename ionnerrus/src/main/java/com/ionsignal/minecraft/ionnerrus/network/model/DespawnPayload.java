package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import com.fasterxml.jackson.annotation.*;

import java.util.UUID;

public record DespawnPayload(
        @JsonProperty("ownerId") UUID ownerId,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("type") String type) implements IonCommand {
}