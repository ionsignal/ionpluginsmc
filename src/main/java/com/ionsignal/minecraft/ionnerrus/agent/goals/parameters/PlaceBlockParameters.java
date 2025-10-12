package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record PlaceBlockParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The name of the material to place (e.g., 'CRAFTING_TABLE', 'OAK_LOG').") String materialName) {
}
