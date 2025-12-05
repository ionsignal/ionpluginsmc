package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState;
import com.ionsignal.minecraft.ionnerrus.agent.debug.AgentVisualizationProvider;
import com.ionsignal.minecraft.ionnerrus.agent.debug.CognitiveDebugState;
import com.ionsignal.minecraft.ionnerrus.agent.debug.CognitiveVisualizationProvider;
import com.ionsignal.minecraft.ionnerrus.listeners.DebugIntegrationListener;

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
            
            Bukkit.getPluginManager().registerEvents(
                new DebugIntegrationListener(plugin.getLogger()), 
                plugin
            );
            
            plugin.getLogger().info("Registered debug visualization providers and lifecycle listeners with IonCore.");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not register with IonCore: " + e.getMessage());
        }
    }

    /**
     * Cleans up external plugin integrations with a best-effort approach where failures are logged but
     * they don't block the shutdown sequence.
     */
    public void cleanup() {
        try {
            IonCore core = (IonCore) Bukkit.getPluginManager().getPlugin("IonCore");
            if (core == null) {
                return;
            }
            try {
                com.ionsignal.minecraft.ionnerrus.agent.AgentService agentService = plugin.getAgentService();
                if (agentService != null) {
                    int cancelledCount = 0;
                    for (com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent agent : agentService.getAgents()) {
                        boolean cancelled = IonCore.getDebugRegistry().cancelSession(
                                agent.getPersona().getUniqueId());
                        if (cancelled) {
                            cancelledCount++;
                        }
                    }
                    if (cancelledCount > 0) {
                        plugin.getLogger().info("Cancelled " + cancelledCount + " debug session(s) for agents.");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error cancelling debug sessions: " + e.getMessage());
            }
            IonCore.getVisualizationRegistry().unregister(AgentDebugState.class);
            IonCore.getVisualizationRegistry().unregister(CognitiveDebugState.class);
            plugin.getLogger().info("Unregistered visualization providers from IonCore.");

        } catch (Exception e) {
            plugin.getLogger().warning("Could not clean up IonCore integration: " + e.getMessage());
        }
    }
}