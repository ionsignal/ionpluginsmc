package com.ionsignal.minecraft.ioncore.network.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Transport envelope for all inbound commands from the Web layer.
 * Provides metadata (ID, timestamp) and contains the typed command payload.
 */
public record CommandEnvelope(
        @JsonProperty("id") UUID id,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("payload") IonCommand payload) {
    /**
     * Validates the envelope structure.
     * 
     * @throws IllegalArgumentException
     *             if payload is null
     */
    public CommandEnvelope {
        if (payload == null) {
            throw new IllegalArgumentException("CommandEnvelope payload cannot be null");
        }
    }
}