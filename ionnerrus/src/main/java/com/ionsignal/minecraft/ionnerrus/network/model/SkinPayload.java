package com.ionsignal.minecraft.ionnerrus.network.model;

import com.fasterxml.jackson.annotation.*;

public record SkinPayload(
        @JsonProperty("type") SkinType type,
        @JsonProperty("mojangTextureValue") String mojangTextureValue,
        @JsonProperty("mojangTextureSignature") String mojangTextureSignature) {
}