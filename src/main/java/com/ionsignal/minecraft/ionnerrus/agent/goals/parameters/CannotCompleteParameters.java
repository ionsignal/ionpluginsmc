package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record CannotCompleteParameters(
        @JsonProperty(required = true) @JsonPropertyDescription("A clear explanation about why the objective failed.") String reason) {
}