package com.ionsignal.minecraft.ionnerrus.network.messages;

/**
 * Represents the JSON payload to remove an agent.
 */
public record DespawnAgentRequest(String name) {
}