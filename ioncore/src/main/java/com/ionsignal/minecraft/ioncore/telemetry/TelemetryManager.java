package com.ionsignal.minecraft.ioncore.telemetry;

import com.ionsignal.minecraft.ioncore.IonCore;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the periodic polling and batching of telemetry data.
 * Implements the "Silent Listener" pattern.
 */
public class TelemetryManager {
    private final IonCore plugin;
    private final CopyOnWriteArrayList<TelemetrySource> sources = new CopyOnWriteArrayList<>();
    private TelemetryTask task;

    // Configurable interval (default 10 ticks / 0.5s)
    private static final long POLL_INTERVAL = 10L;

    public TelemetryManager(IonCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task == null) {
            task = new TelemetryTask();
            task.runTaskTimer(plugin, POLL_INTERVAL, POLL_INTERVAL);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        sources.clear();
    }

    public void register(TelemetrySource source) {
        sources.add(source);
    }

    public void unregister(String sourceId) {
        sources.removeIf(s -> s.getSourceId().equals(sourceId));
    }

    /**
     * Polling task running on the Main Thread.
     */
    private class TelemetryTask extends BukkitRunnable {
        @Override
        public void run() {
            if (sources.isEmpty())
                return;

            // 1. Capture Phase (Sync - Main Thread)
            List<Object> batch = new ArrayList<>(sources.size());
            for (TelemetrySource source : sources) {
                try {
                    Object snapshot = source.captureSnapshot();
                    if (snapshot != null) {
                        batch.add(snapshot);
                    }
                } catch (Exception e) {
                    // Prevent one bad agent from crashing the telemetry loop
                    plugin.getLogger().warning("Error capturing telemetry for " + source.getSourceId() + ": " + e.getMessage());
                }
            }

            // 2. Broadcast Phase (Async Handoff handled by Container)
            if (!batch.isEmpty()) {
                // "BATCH_TELEMETRY" is a new event type for the dashboard to handle
                plugin.getServiceContainer().broadcast("BATCH_TELEMETRY", batch);
            }
        }
    }
}