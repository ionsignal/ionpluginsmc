package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;

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
            @SuppressWarnings("unused")
            IonCore core = (IonCore) Bukkit.getPluginManager().getPlugin("IonCore");
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
            @SuppressWarnings("unused")
            IonCore core = (IonCore) Bukkit.getPluginManager().getPlugin("IonCore");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not clean up IonCore integration: " + e.getMessage());
        }
    }
}