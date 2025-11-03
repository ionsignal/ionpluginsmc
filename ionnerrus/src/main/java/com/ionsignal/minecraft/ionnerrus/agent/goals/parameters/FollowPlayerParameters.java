package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record FollowPlayerParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The name of the player or agent to follow.") String targetName,
        @JsonProperty(required = true) @JsonPropertyDescription("The distance in blocks to maintain from the target.") double followDistance,
        @JsonProperty(required = true) @JsonPropertyDescription("The distance in blocks at which to stop approaching the target.") double stopDistance) {
}