package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.annotation.*;

import java.util.UUID;

public record UpdatePayload(
        @JsonProperty("ownerId") UUID ownerId,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("type") String type,
        @Nullable @JsonProperty("profile") PersonaProfile profile)
        implements IonCommand {
}