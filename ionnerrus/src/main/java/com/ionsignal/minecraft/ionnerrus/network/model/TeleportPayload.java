package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import com.fasterxml.jackson.annotation.*;

import java.util.UUID;

public record TeleportPayload(
        @JsonProperty("ownerId") UUID ownerId,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("type") String type,
        @JsonProperty("location") Location location)
        implements IonCommand {
}