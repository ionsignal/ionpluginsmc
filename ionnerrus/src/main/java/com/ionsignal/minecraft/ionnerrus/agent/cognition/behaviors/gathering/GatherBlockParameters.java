package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.gathering;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GatherBlockParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The block group to collect.") String groupName,
        @JsonProperty(required = true) @JsonPropertyDescription("The number of blocks to collect.") int quantity) {
}