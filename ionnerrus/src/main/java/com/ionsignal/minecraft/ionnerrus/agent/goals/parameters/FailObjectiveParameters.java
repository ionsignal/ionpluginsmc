package com.ionsignal.minecraft.ionnerrus.agent.goals.parameters;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record FailObjectiveParameters(
                @JsonProperty(required = true) @JsonPropertyDescription("The specific category of failure.") FailureType failureType,
                @JsonProperty(required = true) @JsonPropertyDescription("A brief, user-facing explanation for the failure.") String explanation) {

        /**
         * Defines the structured categories for why an objective might fail.
         */
        public enum FailureType {
                /**
                 * Use this when the objective is impossible due to a specific limitation mentioned in the system
                 * prompt (e.g., crafting, deep digging).
                 */
                AGENT_LIMITATION,
                /**
                 * Use this when the objective is possible in Minecraft, but you do not have a specific tool to
                 * perform the action (e.g., fishing, enchanting, trading).
                 */
                MISSING_CAPABILITY,
                /**
                 * Use this when the objective is nonsensical, logically impossible within the rules of Minecraft,
                 * or refers to non-existent items/entities.
                 */
                INVALID_REQUEST,
                /**
                 * Use this when the objective is too vague or lacks the specific details needed to select a tool
                 * and its parameters.
                 */
                AMBIGUOUS_REQUEST
        }
}