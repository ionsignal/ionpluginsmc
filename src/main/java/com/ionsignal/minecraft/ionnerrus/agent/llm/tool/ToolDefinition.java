package com.ionsignal.minecraft.ionnerrus.agent.llm.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
// CHANGE START: Import BiFunction and NerrusAgent for the new enhancer signature.
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import java.util.function.BiFunction;
// CHANGE END
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
// CHANGE START: The schemaEnhancer is now a BiFunction that accepts a NerrusAgent.
public record ToolDefinition(
        String name,
        String description,
        Class<?> parametersClass,
        BiFunction<ObjectNode, NerrusAgent, ObjectNode> schemaEnhancer) {
    // CHANGE END
    /**
     * Convenience constructor for tools that do not require dynamic schema enhancement.
     */
    public ToolDefinition(String name, String description, Class<?> parametersClass) {
        // CHANGE START: The identity function now needs to match the BiFunction signature.
        this(name, description, parametersClass, (schema, agent) -> schema);
        // CHANGE END
    }
}