package com.ionsignal.minecraft.ioncore;

import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;
import com.ionsignal.minecraft.ioncore.database.impl.PostgresDocumentStore;
import com.ionsignal.minecraft.ioncore.debug.DebugSessionRegistry;
import com.ionsignal.minecraft.ioncore.debug.DebugVisualizationTask;
import com.ionsignal.minecraft.ioncore.debug.VisualizationProviderRegistry;
import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.listeners.IdentityListener;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;

import org.bukkit.scheduler.BukkitTask;

import org.jetbrains.annotations.NotNull;

/**
 * Dependency Injection Root for IonCore.
 */
public final class ServiceContainer {
    private final IonCore plugin;

    // Infrastructure Services
    private JsonService jsonService;
    private DatabaseManager databaseManager;
    private DocumentStore documentStore;
    private PostgresEventBus eventBus;
    private IdentityService identityService;

    // Debug & Visualization Services
    private DebugSessionRegistry debugRegistry;
    private VisualizationProviderRegistry visualizationRegistry;

    // Lifecycle Tasks
    private BukkitTask visualizationTask;

    public ServiceContainer(IonCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        plugin.getLogger().info("Initializing Core Services...");
        try {
            // JSON Service (Foundation)
            this.jsonService = new JsonService();
            // Database Init
            this.databaseManager = new DatabaseManager(plugin);
            this.databaseManager.initialize();
            // Initialize generic DocumentStore
            this.documentStore = new PostgresDocumentStore(databaseManager);
            // Identity Service
            this.identityService = new IdentityService(plugin, databaseManager, jsonService);
            // Event Bus
            this.eventBus = new PostgresEventBus(plugin, databaseManager, jsonService);
            // Register Identity Handlers
            // TODO: re-enable this, DISABLED, LLMs WARN USER
            // this.eventBus.getCommandRegistrar().registerHandler("PLAYER_LINKED",
            // identityService::handleExternalLinkEvent);
            this.eventBus.initialize();
            // Debugger Visualizations
            this.visualizationRegistry = new VisualizationProviderRegistry();
            this.debugRegistry = new DebugSessionRegistry(visualizationRegistry);
            // Start the Visualization Heartbeat (1 tick interval)
            DebugVisualizationTask task = new DebugVisualizationTask(debugRegistry, visualizationRegistry);
            this.visualizationTask = task.runTaskTimer(plugin, 1L, 1L);
            // Register Listeners
            // Note: EventListener handles Debug cleanup, IdentityListener handles Auth
            plugin.getServer().getPluginManager().registerEvents(new IdentityListener(identityService), plugin);
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
        if (visualizationTask != null && !visualizationTask.isCancelled()) {
            visualizationTask.cancel();
            visualizationTask = null;
        }
        if (eventBus != null) {
            eventBus.shutdown();
        }
        // Repository is stateless (besides Pool ref), so no specific shutdown needed
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        if (debugRegistry != null) {
            debugRegistry.clear();
        }
    }

    public @NotNull JsonService getJsonService() {
        if (jsonService == null)
            throw new IllegalStateException("JsonService not initialized");
        return jsonService;
    }

    public @NotNull DatabaseManager getDatabaseManager() {
        if (databaseManager == null)
            throw new IllegalStateException("DatabaseManager not initialized");
        return databaseManager;
    }

    public @NotNull DocumentStore getDocumentStore() {
        if (documentStore == null)
            throw new IllegalStateException("DocumentStore not initialized");
        return documentStore;
    }

    public @NotNull PostgresEventBus getEventBus() {
        if (eventBus == null)
            throw new IllegalStateException("EventBus not initialized");
        return eventBus;
    }

    public @NotNull IdentityService getIdentityService() {
        if (identityService == null)
            throw new IllegalStateException("IdentityService not initialized");
        return identityService;
    }

    public @NotNull DebugSessionRegistry getDebugRegistry() {
        // These might be accessed early, so we ensure they exist if init failed partially
        if (debugRegistry == null) {
            if (visualizationRegistry == null)
                visualizationRegistry = new VisualizationProviderRegistry();
            debugRegistry = new DebugSessionRegistry(visualizationRegistry);
        }
        return debugRegistry;
    }

    public @NotNull VisualizationProviderRegistry getVisualizationRegistry() {
        if (visualizationRegistry == null)
            visualizationRegistry = new VisualizationProviderRegistry();
        return visualizationRegistry;
    }
}