package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.sashirestela.openai.common.tool.ToolType;
import io.github.sashirestela.openai.common.tool.Tool;

import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.goals.ParameterDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class LLMToolBuilder {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static List<Tool> fromGoalDefinitions(Collection<GoalDefinition> goalDefinitions) {
        List<Tool> tools = new ArrayList<>();
        for (GoalDefinition goalDef : goalDefinitions) {
            tools.add(createToolFromGoal(goalDef));
        }
        return tools;
    }

    private static Tool createToolFromGoal(GoalDefinition goalDef) {
        ObjectNode propertiesNode = objectMapper.createObjectNode();
        ArrayNode requiredPropertiesNode = objectMapper.createArrayNode();
        for (Map.Entry<String, ParameterDefinition> entry : goalDef.parameters().entrySet()) {
            String paramName = entry.getKey();
            ParameterDefinition paramDef = entry.getValue();
            ObjectNode propertyNode = objectMapper.createObjectNode();
            propertyNode.put("type", mapJavaTypeToJsonSchemaType(paramDef.type()));
            propertyNode.put("description", paramDef.description());
            propertiesNode.set(paramName, propertyNode);
            if (paramDef.required()) {
                requiredPropertiesNode.add(paramName);
            }
        }
        ObjectNode parametersSchema = objectMapper.createObjectNode();
        parametersSchema.put("type", "object");
        parametersSchema.set("properties", propertiesNode);
        if (!requiredPropertiesNode.isEmpty()) {
            parametersSchema.set("required", requiredPropertiesNode);
        }
        return new Tool(ToolType.FUNCTION, new Tool.ToolFunctionDef(
                goalDef.name(), goalDef.description(), parametersSchema, true));
    }

    private static String mapJavaTypeToJsonSchemaType(String javaType) {
        return switch (javaType.toLowerCase()) {
            case "string" -> "string";
            case "int", "integer", "long" -> "integer";
            case "double", "float" -> "number";
            case "boolean" -> "boolean";
            default -> throw new IllegalArgumentException("Unsupported parameter type for LLM tool schema: " + javaType);
        };
    }
}