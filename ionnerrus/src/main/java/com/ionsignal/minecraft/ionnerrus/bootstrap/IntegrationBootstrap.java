package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState;
import com.ionsignal.minecraft.ionnerrus.agent.debug.AgentVisualizationProvider;
import com.ionsignal.minecraft.ionnerrus.agent.debug.CognitiveDebugState;
import com.ionsignal.minecraft.ionnerrus.agent.debug.CognitiveVisualizationProvider;

import org.bukkit.Bukkit;

/**
 * Manages integrations with external plugins.
 * All integration logic is isolated here to prevent contaminating the main plugin class.
 */
public class IntegrationBootstrap {
    private final IonNerrus plugin;

    public IntegrationBootstrap(IonNerrus plugin) {
        this.plugin = plugin;
    }

    /**
     * Attempts to integrate with IonCore debug system.
     * Non-fatal if IonCore is not present.
     */
    public void initializeIonCoreIntegration() {
        try {
            IonCore core = (IonCore) Bukkit.getPluginManager().getPlugin("IonCore");
            if (core == null) {
                plugin.getLogger().warning("IonCore plugin not found. Debug visualizations will be unavailable.");
                return;
            }
            IonCore.getVisualizationRegistry().register(AgentDebugState.class, new AgentVisualizationProvider());
            IonCore.getVisualizationRegistry().register(CognitiveDebugState.class, new CognitiveVisualizationProvider());
            plugin.getLogger().info("Registered debug visualization providers with IonCore.");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not register with IonCore: " + e.getMessage());
        }
    }
}