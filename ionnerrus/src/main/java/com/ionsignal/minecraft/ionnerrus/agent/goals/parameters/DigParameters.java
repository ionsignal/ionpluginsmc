package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record DigParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The number of blocks to dig downwards.") int depth) {
}