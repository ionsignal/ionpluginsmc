package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.guardrails;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record CraftItemParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The name of the item to craft (e.g., 'CRAFTING_TABLE', 'STONE_AXE').") String itemName,
        @JsonProperty(required = true) @JsonPropertyDescription("The number of items to craft.") int quantity) {
}