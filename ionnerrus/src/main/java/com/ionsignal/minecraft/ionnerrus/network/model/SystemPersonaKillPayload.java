package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import java.util.UUID;

import com.fasterxml.jackson.annotation.*;

public record SystemPersonaKillPayload(
        @JsonProperty("type") String type,
        @JsonProperty("sessionId") UUID sessionId) implements IonCommand {
}
