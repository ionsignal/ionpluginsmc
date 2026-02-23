package com.ionsignal.minecraft.ioncore.network.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Standard envelope for inbound commands (Web -> Java).
 */
public record CommandEnvelope(UUID id, long timestamp, JsonNode payload) {
}