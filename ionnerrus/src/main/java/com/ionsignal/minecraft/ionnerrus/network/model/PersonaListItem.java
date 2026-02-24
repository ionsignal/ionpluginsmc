package com.ionsignal.minecraft.ionnerrus.network.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record PersonaListItem(
        @JsonProperty("id") UUID id,
        @JsonProperty("name") String name) {
}