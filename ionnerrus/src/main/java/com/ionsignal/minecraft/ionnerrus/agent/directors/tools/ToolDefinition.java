package com.ionsignal.minecraft.ionnerrus.agent.directors.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;

import java.util.function.BiFunction;

/**
 * Defines a tool for the LLM, linking its name, description, and parameter DTO.
 * It also provides a hook to programmatically enhance the auto-generated JSON schema.
 *
 * @param name
 *            The name of the tool function.
 * @param description
 *            A description of what the tool does.
 * @param parametersClass
 *            The record class that defines the tool's parameters.
 * @param schemaEnhancer
 *            A function to modify the schema after it's been generated.
 */
public record ToolDefinition(
        String name,
        String description,
        Class<?> parametersClass,
        BiFunction<ObjectNode, NerrusAgent, ObjectNode> schemaEnhancer) {
    /**
     * Convenience constructor for tools that do not require dynamic schema enhancement.
     */
    public ToolDefinition(String name, String description, Class<?> parametersClass) {
        this(name, description, parametersClass, (schema, agent) -> schema);
    }
}