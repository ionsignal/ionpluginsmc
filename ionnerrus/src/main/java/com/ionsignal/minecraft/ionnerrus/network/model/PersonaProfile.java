package com.ionsignal.minecraft.ionnerrus.network.model;

import com.fasterxml.jackson.annotation.*;

import org.jetbrains.annotations.Nullable;

public record PersonaProfile(
        @JsonProperty("name") String name,
        @Nullable @JsonProperty("skin") SkinPayload skin) {
}