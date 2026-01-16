package com.ionsignal.minecraft.ionnerrus.network.schema;

import java.util.List;
import java.util.UUID;

/**
 * Defines payloads sent to the Web Dashboard (Egress).
 * These records represent state updates, telemetry, and events.
 */
public final class Outgoing {

    private Outgoing() {}

    /**
     * Represents the static state of an Agent (Identity & Location).
     *
     * @param id           The in-game Entity UUID.
     * @param definitionId The Web Identity UUID.
     * @param name         The display name.
     * @param status       The current lifecycle status (e.g., "IDLE", "BUSY").
     * @param location     The current physical location.
     */
    public record AgentState(
        UUID id,
        UUID definitionId,
        String name,
        String status,
        Shared.LocationData location
    ) {}

    /**
     * High-frequency telemetry data for real-time monitoring.
     *
     * @param personaId   The Web Identity UUID (definitionId).
     * @param vitals      Current health and food status.
     * @param position    Current physical location.
     * @param velocity    Current movement vector.
     * @param currentGoal The simple name of the active goal.
     * @param goalStack   List of goals currently in the behavior stack.
     */
    public record AgentTelemetry(
        UUID personaId,
        Vitals vitals,
        Shared.LocationData position,
        Shared.Vector3 velocity,
        String currentGoal,
        List<String> goalStack
    ) {
        /**
         * Nested record for biological status.
         */
        public record Vitals(
            double health,
            double maxHealth,
            int foodLevel,
            float saturation
        ) {}
    }
    
    /**
     * Update payload for inventory changes.
     * 
     * @param personaId The Web Identity UUID.
     * @param items     List of items (simplified representation).
     */
    public record InventoryUpdate(
        UUID personaId,
        List<String> items
    ) {}

    /**
     * Event payload for goal lifecycle changes (Start/Stop).
     * 
     * @param personaId The Web Identity UUID.
     * @param goalName  The name of the goal.
     * @param event     The event type (e.g., "STARTED", "FINISHED", "FAILED").
     */
    public record GoalEvent(
        UUID personaId,
        String goalName,
        String event
    ) {}
}