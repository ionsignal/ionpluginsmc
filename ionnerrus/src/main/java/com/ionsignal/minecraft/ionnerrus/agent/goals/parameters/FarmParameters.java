package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record FarmParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The type of crop to farm (e.g., 'wheat', 'carrots', 'potatoes').") String cropType,
        @JsonProperty() @JsonPropertyDescription("The action to perform (e.g., 'plant', 'harvest', 'till').") String action) {
}