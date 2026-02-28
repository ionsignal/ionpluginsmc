package com.ionsignal.minecraft.ioncore;

import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;
import com.ionsignal.minecraft.ioncore.database.impl.PostgresDocumentStore;
import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.listeners.IdentityListener;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dependency Injection Root for IonCore.
 */
public final class ServiceContainer {
    private final IonCore plugin;

    private JsonService jsonService;
    private DatabaseManager databaseManager;
    private DocumentStore documentStore;
    private PostgresEventBus eventBus;
    private IdentityService identityService;
    private ExecutorService virtualThreadExecutor;

    public ServiceContainer(IonCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        plugin.getLogger().info("Initializing Core Services...");
        try {
            // Instantiate VT Executor
            this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            // Instantiate JsonService
            this.jsonService = new JsonService();
            // Instantiate DatabaseManager
            this.databaseManager = new DatabaseManager(plugin);
            this.databaseManager.initialize();
            // Instantiate DocumentStore
            this.documentStore = new PostgresDocumentStore(databaseManager);
            // Instantiate IdentityService
            this.identityService = new IdentityService(plugin, databaseManager, jsonService);
            // Instantiate PostgresEventBus
            this.eventBus = new PostgresEventBus(plugin, databaseManager, jsonService);
            this.eventBus.initialize();
            // Register Listeners
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
        if (eventBus != null) {
            eventBus.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdown();
            try {
                if (!virtualThreadExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("VirtualThreadExecutor did not terminate gracefully. Forcing shutdown.");
                    virtualThreadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                virtualThreadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
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

    public @NotNull ExecutorService getVirtualThreadExecutor() {
        if (virtualThreadExecutor == null)
            throw new IllegalStateException("VirtualThreadExecutor not initialized");
        return virtualThreadExecutor;
    }
}