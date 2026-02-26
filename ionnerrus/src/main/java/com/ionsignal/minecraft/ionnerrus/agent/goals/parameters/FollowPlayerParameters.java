package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record FollowPlayerParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("The name of the player or agent to follow.") String targetName,
        @JsonProperty(required = true) @JsonPropertyDescription("The distance in blocks to maintain from the target.") double followDistance,
        @JsonProperty(required = true) @JsonPropertyDescription("The distance in blocks at which to stop approaching the target.") double stopDistance,
        @JsonProperty(required = true) @JsonPropertyDescription("Duration in seconds to follow the target before stopping. Typical values: 30s for brief escort, 120s for patrol, 300s for extended guard duty.") double duration) {

    public FollowPlayerParameters {
        if (duration <= 5.0) {
            duration = 5.0;
        }
        if (duration > 600.0) {
            duration = 600.0;
        }
    }
}