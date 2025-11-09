package com.ionsignal.minecraft.ioncore;

import com.ionsignal.minecraft.ioncore.debug.DebugSessionRegistry;
import com.ionsignal.minecraft.ioncore.debug.DebugVisualizationTask;
import com.ionsignal.minecraft.ioncore.debug.VisualizationProviderRegistry;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * IonCore - Core debug framework for Ion Signal plugins.
 * Provides shared registry and functionality accessible to all dependent plugins.
 */
public class IonCore extends JavaPlugin implements Listener {
    private static IonCore instance;
    private static DebugSessionRegistry debugRegistry;
    private static VisualizationProviderRegistry visualizationRegistry;

    @Override
    public void onEnable() {
        instance = this;
        // Initialize shared debug registry
        debugRegistry = new DebugSessionRegistry();
        // Initialize visualization provider registry
        visualizationRegistry = new VisualizationProviderRegistry();
        // This task renders all dirty debug sessions once per tick on the main thread
        new DebugVisualizationTask(debugRegistry, visualizationRegistry)
                .runTaskTimer(this, 0L, 1L);
        // Register event listeners for session lifecycle
        getServer().getPluginManager().registerEvents(this, this);
        // Log initialization with modern API
        getLogger().info("IonCore v" + getPluginMeta().getVersion() + " initialized. Debug framework ready.");
    }

    @Override
    public void onDisable() {
        // Clean up all debug sessions on shutdown
        if (debugRegistry != null) {
            int sessionCount = debugRegistry.size();
            debugRegistry.clear();
            getLogger().info("Cleaned up " + sessionCount + " debug session(s).");
        }
        // Clean up visualization providers on shutdown
        if (visualizationRegistry != null) {
            visualizationRegistry.clear();
        }
        getLogger().info("IonCore has been disabled.");
    }

    /**
     * Handles player logout by cleaning up their debug sessions.
     * Automatic session cleanup on player disconnect.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (debugRegistry != null) {
            boolean hadSession = debugRegistry.cancelSession(event.getPlayer().getUniqueId());
            if (hadSession) {
                getLogger().info("Cancelled debug session for player " + event.getPlayer().getName() + " (logout)");
            }
        }
    }

    /**
     * Gets the singleton instance of IonCore.
     * Static accessor for dependent plugins.
     *
     * @return The IonCore plugin instance.
     * @throws IllegalStateException
     *             if IonCore is not loaded.
     */
    public static IonCore getInstance() {
        if (instance == null) {
            throw new IllegalStateException("IonCore is not loaded. Ensure it is listed in plugin dependencies.");
        }
        return instance;
    }

    /**
     * Gets the shared debug session registry.
     * Primary service accessor for debug framework.
     *
     * @return The global debug session registry.
     * @throws IllegalStateException
     *             if IonCore is not loaded.
     */
    public static DebugSessionRegistry getDebugRegistry() {
        if (debugRegistry == null) {
            throw new IllegalStateException("Debug registry is not initialized. IonCore may not be enabled.");
        }
        return debugRegistry;
    }

    /**
     * Gets the shared visualization provider registry.
     * Visualization providers register here to handle rendering for specific debug state types.
     *
     * Plugins call this to register their visualization implementations for custom debug state types.
     * This allows decoupled rendering logic that can vary by plugin while reusing the core
     * session management infrastructure.
     *
     * @return The global visualization provider registry
     * @throws IllegalStateException
     *             if IonCore is not loaded or not enabled
     */
    public static VisualizationProviderRegistry getVisualizationRegistry() {
        if (visualizationRegistry == null) {
            throw new IllegalStateException(
                    "Visualization registry is not initialized. IonCore may not be enabled.");
        }
        return visualizationRegistry;
    }
}