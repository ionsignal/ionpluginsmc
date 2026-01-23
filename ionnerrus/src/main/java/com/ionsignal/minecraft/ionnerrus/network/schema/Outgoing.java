package com.ionsignal.minecraft.ionnerrus.network.schema;

import java.util.UUID;

/**
 * Defines payloads sent to the Web Dashboard (Egress).
 * These records represent state updates, telemetry, and events.
 */
public final class Outgoing {
    private Outgoing() {
    }

    /**
     * Represents the static state of an Agent (Identity & Location).
     *
     * @param id
     *            The in-game Entity UUID.
     * @param definitionId
     *            The Web Identity UUID.
     * @param name
     *            The display name.
     * @param status
     *            The current lifecycle status (e.g., "IDLE", "BUSY").
     * @param location
     *            The current physical location.
     */
    public record AgentState(
            UUID id,
            UUID definitionId,
            String name,
            String status,
            Shared.LocationData location) {
    }
}