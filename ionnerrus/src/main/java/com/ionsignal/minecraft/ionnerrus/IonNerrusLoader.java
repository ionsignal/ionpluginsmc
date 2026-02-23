package com.ionsignal.minecraft.ionnerrus;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;

import org.jetbrains.annotations.NotNull;

/**
 * Plugin Loader for IonNerrus.
 */
@SuppressWarnings("UnstableApiUsage")
public class IonNerrusLoader implements PluginLoader {

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        // No-op: Libraries are inherited from IonCore
    }
}