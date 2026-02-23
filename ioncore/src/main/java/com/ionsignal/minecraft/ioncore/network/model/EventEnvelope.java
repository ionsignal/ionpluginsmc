package com.ionsignal.minecraft.ioncore.network.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Standard envelope for outbound events (Java -> Web).
 */
public record EventEnvelope(UUID id, long timestamp, JsonNode payload) {
}