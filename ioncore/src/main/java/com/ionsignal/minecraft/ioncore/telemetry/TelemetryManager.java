package com.ionsignal.minecraft.ioncore.telemetry;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the collection and transmission of server telemetry.
 * <p>
 * Refactored for Phase 4: Now uses {@link PostgresEventBus} for transmission.
 */
public final class TelemetryManager {

    private final IonCore plugin;
    private PostgresEventBus eventBus;
    private final Map<String, Object> staticMetadata = new ConcurrentHashMap<>();

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
     * @param category The category of the event (e.g., "performance", "agent_lifecycle").
     * @param data     The data object to serialize.
     */
    public void sendTelemetry(@NotNull String category, @NotNull Object data) {
        if (eventBus == null || !eventBus.isRunning()) {
            // Fail silently if bus isn't ready (common during startup/shutdown)
            return;
        }

        // We wrap the data in a telemetry-specific structure if needed,
        // or just pass it through. Here we pass it as a "TELEMETRY" event type.
        // The 'category' becomes part of the payload.
        
        TelemetryEnvelope envelope = new TelemetryEnvelope(category, data);
        eventBus.broadcast("TELEMETRY", envelope);
    }

    /**
     * Simple DTO for telemetry payloads.
     */
    private record TelemetryEnvelope(String category, Object data) {}
}