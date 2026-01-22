package com.ionsignal.minecraft.ioncore.telemetry;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;

import org.jetbrains.annotations.NotNull;

public final class TelemetryManager {
    @SuppressWarnings("unused")
    private final IonCore plugin;
    private PostgresEventBus eventBus;

    public TelemetryManager(IonCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Injects the EventBus reference.
     * Called during CoreServiceContainer initialization.
     */
    public void setEventBus(PostgresEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Sends a telemetry event to the director.
     *
     * @param category
     *            The category of the event (e.g., "performance", "agent_lifecycle").
     * @param data
     *            The data object to serialize.
     */
    public void sendTelemetry(@NotNull String category, @NotNull Object data) {
        if (eventBus == null)
            return;
        TelemetryEnvelope envelope = new TelemetryEnvelope(category, data);
        eventBus.broadcast("TELEMETRY", envelope);
    }

    private record TelemetryEnvelope(String category, Object data) {
    }
}