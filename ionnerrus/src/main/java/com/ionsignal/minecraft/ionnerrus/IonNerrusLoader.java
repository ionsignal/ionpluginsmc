package com.ionsignal.minecraft.ionnerrus;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import org.jetbrains.annotations.NotNull;

public class IonNerrusLoader implements PluginLoader {
    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        // Intentionally left empty.
        // All Jackson, Kotlin, and serialization libraries are provided by IonCore
        // and inherited via `join-classpath: true` in paper-plugin.yml.
    }
}