package com.ionsignal.minecraft.ionnerrus.agent.goals.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FailObjectiveParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;

import java.util.Arrays;
import java.util.stream.Collectors;

public class FailObjectiveToolProvider implements GoalProvider {
    @Override
    public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
        return new ToolDefinition(
                "FAIL_OBJECTIVE",
                "Call this function if and only if the user's objective cannot be achieved with the available tools for a specific, identifiable reason.",
                FailObjectiveParameters.class,
                (schema, agent) -> {
                    // Enhance the schema to include the enum values in the description,
                    // making it explicit to the LLM what its choices are.
                    String validTypes = Arrays.stream(FailObjectiveParameters.FailureType.values())
                            .map(Enum::name)
                            .collect(Collectors.joining(", "));

                    ObjectNode properties = (ObjectNode) schema.get("properties");
                    if (properties != null) {
                        ObjectNode failureTypeProp = (ObjectNode) properties.get("failureType");
                        if (failureTypeProp != null) {
                            String currentDesc = failureTypeProp.get("description").asText();
                            failureTypeProp.put("description", currentDesc + " Must be one of: " + validTypes);
                        }
                    }
                    return schema;
                });
    }
}