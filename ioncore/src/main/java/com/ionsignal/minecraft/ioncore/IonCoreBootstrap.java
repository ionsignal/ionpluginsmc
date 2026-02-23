package com.ionsignal.minecraft.ioncore;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import org.jetbrains.annotations.NotNull;

/**
 * Bootstrap class for IonCore.
 * Required entry point for the modern Paper plugin system.
 * 
 * This class runs before the Bukkit API is initialized.
 * We keep it intentionally empty to avoid race conditions during early startup.
 */
@SuppressWarnings("UnstableApiUsage")
public class IonCoreBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        // No-op: Infrastructure initialization is handled by ServiceContainer in onEnable()
    }
}