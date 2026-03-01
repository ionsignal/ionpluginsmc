package com.ionsignal.minecraft.ionnerrus.agent.directors.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolSchemaFactory {
    private static final SchemaGenerator generator;

    static {
        // Configure Jackson Module to respect annotations like @JsonProperty(required=true)
        JacksonModule jacksonModule = new JacksonModule(
                JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY);
        // Configure Generator for OpenAI Compatibility (Draft 2020-12)
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON)
                        .with(jacksonModule)
                        // OpenAI Strict Mode requires additionalProperties: false
                        .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                        // Ensure enums are serialized as their string values
                        .with(Option.FLATTENED_ENUMS_FROM_TOSTRING);
        // Force all fields to be "required" in the schema, complying with OpenAI Strict Mode
        configBuilder.forFields()
                .withRequiredCheck(field -> true);
        generator = new SchemaGenerator(configBuilder.build());
    }

    public static List<ChatCompletionTool> fromToolDefinitions(Collection<ToolDefinition> toolDefinitions, NerrusAgent agent) {
        List<ChatCompletionTool> tools = new ArrayList<>();
        for (ToolDefinition toolDef : toolDefinitions) {
            tools.add(createToolFromDefinition(toolDef, agent));
        }
        return tools;
    }

    private static ChatCompletionTool createToolFromDefinition(ToolDefinition toolDef, NerrusAgent agent) {
        // Generate the base schema using VicTools
        ObjectNode schema = generator.generateSchema(toolDef.parametersClass());
        // Clean up schema for OpenAI
        // OpenAI schemas should not have root-level $schema, $id, or title keywords
        schema.remove("$schema");
        schema.remove("$id");
        schema.remove("title");
        // Apply the dynamic enhancer function to modify the schema (e.g., injecting valid block types)
        ObjectNode enhancedSchema = toolDef.schemaEnhancer().apply(schema, agent);
        // Bridge Jackson ObjectNode -> Map<String, JsonValue> for openai-java
        Map<String, JsonValue> finalSchemaMap = convertNodeToMap(enhancedSchema);
        // Construct FunctionParameters wrapper
        FunctionParameters parameters = FunctionParameters.builder()
                .additionalProperties(finalSchemaMap)
                .build();
        // Create FunctionDefinition with Strict Mode ENABLED
        FunctionDefinition functionDef = FunctionDefinition.builder()
                .name(toolDef.name())
                .description(toolDef.description())
                .parameters(parameters)
                .strict(true) // OpenAI Structured Outputs (Strict Mode)
                .build();
        // Wrap in ChatCompletionTool
        return ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder()
                        .function(functionDef)
                        .build());
    }

    /**
     * Bridges a Jackson ObjectNode to the Map<String, JsonValue> required by the openai-java library.
     * This recursively converts the entire Jackson tree into the OpenAI SDK's JsonValue types.
     */
    private static Map<String, JsonValue> convertNodeToMap(ObjectNode node) {
        Map<String, JsonValue> map = new HashMap<>();
        node.fields().forEachRemaining(entry -> {
            // JsonValue.fromJsonNode is a static helper in the kotlin companion object of JsonValue
            // It safely converts Jackson types (MISSING, NULL, BOOLEAN, NUMBER, STRING, ARRAY, OBJECT)
            map.put(entry.getKey(), JsonValue.fromJsonNode(entry.getValue()));
        });
        return map;
    }
}