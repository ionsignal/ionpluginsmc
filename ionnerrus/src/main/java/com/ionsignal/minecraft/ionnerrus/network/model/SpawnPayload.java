package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import java.util.UUID;

import com.fasterxml.jackson.annotation.*;

public record SpawnPayload(
        @JsonProperty("owner") IonUser owner,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("sessionId") UUID sessionId,
        @JsonProperty("type") String type,
        @JsonProperty("location") SpawnLocation location)
        implements IonCommand {
}