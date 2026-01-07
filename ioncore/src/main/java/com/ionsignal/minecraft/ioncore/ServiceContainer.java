package com.ionsignal.minecraft.ioncore;

import com.ionsignal.minecraft.ioncore.database.DatabaseManager;
import com.ionsignal.minecraft.ioncore.debug.DebugSessionRegistry;
import com.ionsignal.minecraft.ioncore.debug.VisualizationProviderRegistry;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ioncore.telemetry.TelemetryManager;
import org.jetbrains.annotations.NotNull;

/**
 * Dependency Injection Root for IonCore.
 * <p>
 * MIGRATION STATUS: Phase 5 (PostgreSQL Bus)
 * - Removed: WebSocketServer, WebSocketClient
 * - Added: DatabaseManager, PostgresEventBus
 * - Retained: DebugSessionRegistry, VisualizationProviderRegistry
 */
public final class ServiceContainer {

    private final IonCore plugin;

    // Network & Data Services
    private DatabaseManager databaseManager;
    private PostgresEventBus eventBus;
    private TelemetryManager telemetryManager;

    // Debug & Visualization Services
    private DebugSessionRegistry debugRegistry;
    private VisualizationProviderRegistry visualizationRegistry;

    public ServiceContainer(IonCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        plugin.getLogger().info("Initializing Core Services...");

        try {
            // 1. Database Infrastructure (The Hardware)
            this.databaseManager = new DatabaseManager(plugin);
            this.databaseManager.initialize();

            // 2. Event Bus (The Network)
            this.eventBus = new PostgresEventBus(plugin, databaseManager);
            this.eventBus.initialize();

            // 3. Telemetry (The Application Layer)
            this.telemetryManager = new TelemetryManager(plugin);
            this.telemetryManager.setEventBus(eventBus);

            // 4. Debug & Visualization (Restored)
            this.debugRegistry = new DebugSessionRegistry();
            this.visualizationRegistry = new VisualizationProviderRegistry();

            plugin.getLogger().info("Core Services initialized successfully.");

        } catch (Exception e) {
            plugin.getLogger().severe("CRITICAL: Failed to initialize Core Services.");
            e.printStackTrace();
            // We do not disable the plugin here to allow for debugging,
            // but functionality will be severely limited.
        }
    }

    public void shutdown() {
        plugin.getLogger().info("Shutting down Core Services...");

        if (eventBus != null) {
            eventBus.shutdown();
        }

        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        // Clear debug sessions if necessary
        if (debugRegistry != null) {
            debugRegistry.clear();
        }
    }

    // --- Accessors ---

    public @NotNull DatabaseManager getDatabaseManager() {
        if (databaseManager == null)
            throw new IllegalStateException("DatabaseManager not initialized");
        return databaseManager;
    }

    public @NotNull PostgresEventBus getEventBus() {
        if (eventBus == null)
            throw new IllegalStateException("EventBus not initialized");
        return eventBus;
    }

    public @NotNull TelemetryManager getTelemetryManager() {
        if (telemetryManager == null)
            throw new IllegalStateException("TelemetryManager not initialized");
        return telemetryManager;
    }

    // --- Restored Accessors ---

    public @NotNull DebugSessionRegistry getDebugRegistry() {
        // These might be accessed early, so we ensure they exist if init failed partially
        if (debugRegistry == null)
            debugRegistry = new DebugSessionRegistry();
        return debugRegistry;
    }

    public @NotNull VisualizationProviderRegistry getVisualizationRegistry() {
        if (visualizationRegistry == null)
            visualizationRegistry = new VisualizationProviderRegistry();
        return visualizationRegistry;
    }
}