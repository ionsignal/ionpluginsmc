package com.ionsignal.minecraft.ionnerrus.agent.goals;

/**
 * Defines a parameter for a Goal, including its type, description, and requirement.
 * This is used for programmatic validation and for providing clear "tool use"
 * instructions to an LLM.
 *
 * @param type        A string representation of the expected data type (e.g., "String", "int", "Set<Material>").
 * @param description A human-readable description of what the parameter is for.
 * @param required    Whether this parameter must be provided for the goal to be created.
 */
public record ParameterDefinition(String type, String description, boolean required) {
}