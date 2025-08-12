package com.ionsignal.minecraft.ionnerrus.agent.goals;

import java.util.Map;

/**
 * An immutable definition of a Goal that can be registered and used by a factory
 * or an LLM planner.
 *
 * @param name        The unique identifier for the goal (e.g., "GET_BLOCKS").
 * @param description A human-readable description of what the goal accomplishes.
 * @param parameters  A map of parameter names to their definitions.
 */
public record GoalDefinition(
        String name,
        String description,
        Map<String, ParameterDefinition> parameters) {
}