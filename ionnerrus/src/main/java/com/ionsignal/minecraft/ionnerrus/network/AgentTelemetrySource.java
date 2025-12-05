package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.telemetry.TelemetrySource;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.dtos.AgentTelemetryDTO;
import org.jetbrains.annotations.Nullable;

/**
 * Adapts a NerrusAgent to the IonCore TelemetrySource interface.
 * Allows IonCore to "pull" data from the agent without the agent knowing about networking.
 */
public class AgentTelemetrySource implements TelemetrySource {
    private final NerrusAgent agent;

    public AgentTelemetrySource(NerrusAgent agent) {
        this.agent = agent;
    }

    @Override
    public String getSourceId() {
        return agent.getPersona().getUniqueId().toString();
    }

    @Override
    public @Nullable Object captureSnapshot() {
        // Safety check: Don't report on agents that are despawned or in invalid states
        if (!agent.getPersona().isSpawned()) {
            return null;
        }
        // Delegate to the DTO factory (safe on Main Thread)
        return AgentTelemetryDTO.from(agent);
    }
}