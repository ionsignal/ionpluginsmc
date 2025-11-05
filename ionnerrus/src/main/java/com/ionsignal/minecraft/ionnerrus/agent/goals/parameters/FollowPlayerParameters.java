package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record FollowPlayerParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The name of the player or agent to follow.") String targetName,
        @JsonProperty(required = true) @JsonPropertyDescription("The distance in blocks to maintain from the target.") double followDistance,
        @JsonProperty(required = true) @JsonPropertyDescription("The distance in blocks at which to stop approaching the target.") double stopDistance,
        @JsonProperty(required = true) @JsonPropertyDescription("Duration in seconds to follow the target before stopping. Typical values: 30s for brief escort, 120s for patrol, 300s for extended guard duty.") double duration) {
    public FollowPlayerParameters {
        if (duration <= 0.0) {
            throw new IllegalArgumentException("Duration must be greater than 0 seconds");
        }
        if (duration > 240.0) {
            throw new IllegalArgumentException("Duration cannot exceed 240 seconds (4 minutes)");
        }
    }
}