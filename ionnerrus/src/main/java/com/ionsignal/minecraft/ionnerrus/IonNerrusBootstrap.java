package com.ionsignal.minecraft.ionnerrus;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import org.jetbrains.annotations.NotNull;

/**
 * Bootstrap class for IonNerrus.
 * Required entry point for the modern Paper plugin system.
 */
@SuppressWarnings("UnstableApiUsage")
public class IonNerrusBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        // No-op: Lifecycle handled in IonNerrus.onEnable()
    }
}