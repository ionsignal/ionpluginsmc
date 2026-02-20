package com.ionsignal.minecraft.ioncore.network.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.UUID;

/**
 * Transport envelope for all outbound events to the Web layer.
 */
public record EventEnvelope(
        @JsonProperty("id") UUID id,
        @JsonProperty("timestamp") long timestamp,
        @JsonRawValue @JsonProperty("payload") String payload) {
    /**
     * Compact constructor for validation.
     */
    public EventEnvelope {
        if (payload == null) {
            throw new IllegalArgumentException("EventEnvelope payload cannot be null");
        }
    }
}