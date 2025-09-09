package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record MineOreParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The name of the ore to mine (e.g., 'iron', 'coal', 'diamond').") String oreName,
        @JsonProperty(required = true) @JsonPropertyDescription("The number of ore blocks to mine.") int quantity) {
}