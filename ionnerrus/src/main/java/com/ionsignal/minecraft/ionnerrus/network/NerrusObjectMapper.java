package com.ionsignal.minecraft.ionnerrus.network;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

/**
 * ObjectMapper for IonNerrus network serialization.
 *
 */
public final class NerrusObjectMapper {

    /**
     * Shared, thread-safe ObjectMapper instance.
     * ObjectMapper is safe for concurrent use after its initial configuration is complete.
     */
    public static final ObjectMapper INSTANCE = new ObjectMapper()
            .registerModule(new ParameterNamesModule())
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);

    private NerrusObjectMapper() {
    }
}