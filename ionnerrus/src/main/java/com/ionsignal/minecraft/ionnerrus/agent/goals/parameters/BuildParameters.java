package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record BuildParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The type of structure to build (e.g., 'house', 'wall', 'bridge').") String structureType,
        @JsonProperty() @JsonPropertyDescription("An optional detailed description of the structure to be built.") String description) {
}