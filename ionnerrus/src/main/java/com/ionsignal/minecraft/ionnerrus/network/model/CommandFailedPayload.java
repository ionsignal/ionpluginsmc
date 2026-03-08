package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import com.fasterxml.jackson.annotation.*;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record CommandFailedPayload(
        @JsonProperty("type") String type,
        @JsonProperty("ownerId") UUID ownerId,
        @JsonProperty("reason") String reason,
        @Nullable @JsonProperty("command") String command) implements IonCommand {
}