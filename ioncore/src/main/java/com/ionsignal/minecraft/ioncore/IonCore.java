package com.ionsignal.minecraft.ioncore;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.debug.DebugSessionRegistry;
import com.ionsignal.minecraft.ioncore.debug.VisualizationProviderRegistry;
import com.ionsignal.minecraft.ioncore.listeners.EventListener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * IonCore - Core framework for Ion Signal plugins.
 * Refactored to use strict ServiceContainer pattern.
 */
public class IonCore extends JavaPlugin {
    private static IonCore instance;

    // The single source of truth for all subsystems
    private ServiceContainer serviceContainer;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        try {
            // Initialize Service Container
            this.serviceContainer = new ServiceContainer(this);
            this.serviceContainer.initialize();
            // Register Event Listeners (Moved out of main class)
            getServer().getPluginManager().registerEvents(
                    new EventListener(serviceContainer.getDebugRegistry()),
                    this);
            getLogger().info("IonCore v" + getPluginMeta().getVersion() + " initialized.");
        } catch (ServiceInitializationException e) {
            getLogger().severe("CRITICAL INITIALIZATION FAILURE: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (serviceContainer != null) {
            serviceContainer.shutdown();
            serviceContainer = null;
        }
        getLogger().info("IonCore disabled.");
    }

    public static IonCore getInstance() {
        return instance;
    }

    public ServiceContainer getServiceContainer() {
        return serviceContainer;
    }

    public IdentityService getIdentityService() {
        ensureInitialized();
        return serviceContainer.getIdentityService();
    }

    // --- Legacy / Static Bridge Accessors ---
    // TODO: Update IonNerrus to NOT use these (will be deprecated eventually)
    // These delegate to the container instance to maintain backward compatibility while enforcing the
    // new architectural boundaries.

    public static DebugSessionRegistry getDebugRegistry() {
        ensureInitialized();
        return instance.serviceContainer.getDebugRegistry();
    }

    public static VisualizationProviderRegistry getVisualizationRegistry() {
        ensureInitialized();
        return instance.serviceContainer.getVisualizationRegistry();
    }

    private static void ensureInitialized() {
        if (instance == null || instance.serviceContainer == null) {
            throw new IllegalStateException("IonCore is not initialized. Ensure the plugin is enabled.");
        }
    }
}