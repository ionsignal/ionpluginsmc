package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.sashirestela.openai.common.function.SchemaConverter;
import io.github.sashirestela.openai.common.tool.Tool;
import io.github.sashirestela.openai.common.tool.ToolType;
import io.github.sashirestela.openai.support.DefaultSchemaConverter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class LLMToolBuilder {
    private static final SchemaConverter schemaConverter = new DefaultSchemaConverter();

    public static List<Tool> fromToolDefinitions(Collection<ToolDefinition> toolDefinitions) {
        List<Tool> tools = new ArrayList<>();
        for (ToolDefinition toolDef : toolDefinitions) {
            tools.add(createToolFromDefinition(toolDef));
        }
        return tools;
    }

    private static Tool createToolFromDefinition(ToolDefinition toolDef) {
        // 1. Generate the base schema from the record using the library's converter.
        ObjectNode parametersSchema = (ObjectNode) schemaConverter.convert(toolDef.parametersClass());

        // 2. Apply the dynamic enhancer function to modify the schema.
        ObjectNode enhancedSchema = toolDef.schemaEnhancer().apply(parametersSchema);

        // 3. Create the tool with the final, enhanced schema.
        return new Tool(ToolType.FUNCTION, new Tool.ToolFunctionDef(
                toolDef.name(),
                toolDef.description(),
                enhancedSchema,
                true // strict mode
        ));
    }
}