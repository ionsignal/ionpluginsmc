package com.ionsignal.minecraft.ionnerrus.agent.llm.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Function;

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
        Function<ObjectNode, ObjectNode> schemaEnhancer) {
    /**
     * Convenience constructor for tools that do not require dynamic schema enhancement.
     */
    public ToolDefinition(String name, String description, Class<?> parametersClass) {
        this(name, description, parametersClass, Function.identity());
    }
}