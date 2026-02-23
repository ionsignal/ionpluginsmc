package com.ionsignal.minecraft.ionnerrus.network.model;

import java.util.UUID;

import com.fasterxml.jackson.annotation.*;

import org.jetbrains.annotations.Nullable;

public record AgentConfig(
        @JsonProperty("id") UUID id,
        @JsonProperty("name") String name,
        @Nullable @JsonProperty("skin") Skin skin) {

}