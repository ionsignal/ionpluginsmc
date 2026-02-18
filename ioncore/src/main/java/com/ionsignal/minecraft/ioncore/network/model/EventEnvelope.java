package com.ionsignal.minecraft.ioncore.network.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Transport envelope for all outbound events to the Web layer.
 * <p>
 * This record is static infrastructure in IonCore and is NOT generated.
 */
public record EventEnvelope(
        @JsonProperty("id") UUID id,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("payload") IonEvent payload) {
    /**
     * Compact constructor for validation.
     */
    public EventEnvelope {
        if (payload == null) {
            throw new IllegalArgumentException("EventEnvelope payload cannot be null");
        }
    }
}