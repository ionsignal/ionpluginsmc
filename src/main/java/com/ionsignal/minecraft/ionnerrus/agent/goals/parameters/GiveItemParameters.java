package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GiveItemParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The name of the material to give (e.g., 'OAK_LOG', 'COBBLESTONE').") String materialName,
        @JsonProperty(required = true) @JsonPropertyDescription("The number of items to give.") int quantity,
        @JsonProperty(required = true) @JsonPropertyDescription("The name of the player or agent to give the item to.") String targetName) {
}