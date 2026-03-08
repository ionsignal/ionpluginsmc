package com.ionsignal.minecraft.ioncore;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.config.TenantConfig;
import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.listeners.IdentityListener;
import com.ionsignal.minecraft.ioncore.network.IonEventBroker;
import com.ionsignal.minecraft.ioncore.network.NatsBroker;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ServiceContainer {
    private final IonCore plugin;

    private JsonService jsonService;
    private TenantConfig tenantConfig;
    private NatsBroker eventBroker;
    private IdentityService identityService;
    private ExecutorService virtualThreadExecutor;

    public ServiceContainer(IonCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        plugin.getLogger().info("Initializing Core Services (Stateless Mode)...");
        try {
            // Instantiate VT Executor
            this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            // Instantiate JsonService
            this.jsonService = new JsonService();
            // Instantiate Tenant Config
            this.tenantConfig = new TenantConfig(plugin);
            // Instantiate NatsBroker
            this.eventBroker = new NatsBroker(plugin, tenantConfig, jsonService, virtualThreadExecutor);
            this.eventBroker.initialize();
            // Instantiate IdentityService
            this.identityService = new IdentityService(plugin, eventBroker, tenantConfig, jsonService);
            // Register Listeners
            plugin.getServer().getPluginManager().registerEvents(new IdentityListener(identityService), plugin);
            plugin.getLogger().info("Core Services initialized successfully.");
        } catch (Exception e) {
            plugin.getLogger().severe("CRITICAL: Failed to initialize Core Services.");
            e.printStackTrace();
        }
    }

    public void shutdown() {
        plugin.getLogger().info("Shutting down Core Services...");
        if (eventBroker != null) {
            eventBroker.shutdown();
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

    public @NotNull IonEventBroker getEventBroker() {
        if (eventBroker == null)
            throw new IllegalStateException("EventBroker not initialized");
        return eventBroker;
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

    public @NotNull TenantConfig getTenantConfig() {
        if (tenantConfig == null)
            throw new IllegalStateException("TenantConfig not initialized");
        return tenantConfig;
    }
}