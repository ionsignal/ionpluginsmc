package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record RequestItemParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The name of the material to request (e.g., 'COAL', 'DIAMOND').") String materialName,
        @JsonProperty(required = true) @JsonPropertyDescription("The number of items to request.") int quantity) {
}
