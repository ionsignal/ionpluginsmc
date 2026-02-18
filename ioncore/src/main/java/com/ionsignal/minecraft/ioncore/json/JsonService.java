package com.ionsignal.minecraft.ioncore.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import java.io.IOException;

/**
 * Centralized JSON handling service for IonCore.
 * Enforces a strict Jackson-only policy for all internal data serialization.
 */
public class JsonService {
    private final ObjectMapper objectMapper;

    public JsonService() {
        this.objectMapper = JsonMapper.builder()
                .addModule(new ParameterNamesModule())
                .addModule(new Jdk8Module())
                .addModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .build();
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public String toJson(Object object) {
        try {

            return objectMapper.writeValueAsString(object);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize object to JSON", e);
        }
    }

    public <T> T fromJson(String json, Class<T> clazz) {
        try {
            // Add debug logging for CommandEnvelope deserialization
            if (clazz.equals(com.ionsignal.minecraft.ioncore.network.model.CommandEnvelope.class)) {
                // Log at FINE level to avoid spam
                java.util.logging.Logger.getLogger("IonCore").info(
                        "Deserializing CommandEnvelope from: " + json.substring(0, Math.min(100, json.length())) + "...");
            }
            return objectMapper.readValue(json, clazz);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize JSON to " + clazz.getSimpleName(), e);
        }
    }

    public JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse JSON tree", e);
        }
    }
}