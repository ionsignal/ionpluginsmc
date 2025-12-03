// src/main/java/com/ionsignal/minecraft/ioncore/IonCore.java
package com.ionsignal.minecraft.ioncore;

import com.ionsignal.minecraft.ioncore.debug.DebugSessionRegistry;
import com.ionsignal.minecraft.ioncore.debug.DebugVisualizationTask;
import com.ionsignal.minecraft.ioncore.debug.VisualizationProviderRegistry;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * IonCore - Core framework for Ion Signal plugins.
 * Provides networking and debug infrastructure.
 */
public class IonCore extends JavaPlugin implements Listener {
    private static IonCore instance;
    
    // Debug System
    private static DebugSessionRegistry debugRegistry;
    private static VisualizationProviderRegistry visualizationRegistry;
    
    // Networking System
    private CoreServiceContainer serviceContainer;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        
        // 1. Initialize Debug Framework
        debugRegistry = new DebugSessionRegistry();
        visualizationRegistry = new VisualizationProviderRegistry();
        
        new DebugVisualizationTask(debugRegistry, visualizationRegistry)
                .runTaskTimer(this, 0L, 1L);
        
        getServer().getPluginManager().registerEvents(this, this);
        
        // 2. Initialize Networking Container
        this.serviceContainer = new CoreServiceContainer(this);
        
        getLogger().info("IonCore v" + getPluginMeta().getVersion() + " initialized.");
    }

    @Override
    public void onDisable() {
        // Shutdown Networking
        if (serviceContainer != null) {
            serviceContainer.shutdown();
        }

        // Cleanup Debug Sessions
        if (debugRegistry != null) {
            int sessionCount = debugRegistry.size();
            debugRegistry.clear();
            getLogger().info("Cleaned up " + sessionCount + " debug session(s).");
        }
        if (visualizationRegistry != null) {
            visualizationRegistry.clear();
        }
        
        getLogger().info("IonCore disabled.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (debugRegistry != null) {
            debugRegistry.cancelSession(event.getPlayer().getUniqueId());
        }
    }

    public static IonCore getInstance() {
        return instance;
    }

    /**
     * Access to the Networking Service Container.
     */
    public CoreServiceContainer getServiceContainer() {
        return serviceContainer;
    }

    public static DebugSessionRegistry getDebugRegistry() {
        return debugRegistry;
    }

    public static VisualizationProviderRegistry getVisualizationRegistry() {
        return visualizationRegistry;
    }
}