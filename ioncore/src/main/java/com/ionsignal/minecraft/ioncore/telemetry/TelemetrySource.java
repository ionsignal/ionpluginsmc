package com.ionsignal.minecraft.ioncore.telemetry;

import org.jetbrains.annotations.Nullable;

/**
 * Interface for objects that provide periodic telemetry data.
 * Implemented by plugins (e.g., IonNerrus) to expose agent state to IonCore.
 */
public interface TelemetrySource {
    /**
     * @return A unique identifier for this source (e.g., Agent UUID).
     */
    String getSourceId();

    /**
     * Captures the current state of the source.
     * <p>
     * <b>THREAD SAFETY:</b> This method is guaranteed to be called on the <b>Main Server Thread</b>.
     * It is safe to access Bukkit/NMS entities here.
     *
     * @return A DTO representing the state, or null if the source is currently unavailable/offline.
     */
    @Nullable
    Object captureSnapshot();
}