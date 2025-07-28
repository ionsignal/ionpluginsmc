package com.ionsignal.minecraft.ionnerrus;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class PluginConfig {
    private final FileConfiguration handle;

    public PluginConfig(FileConfiguration handle) {
        this.handle = handle;
    }

    public List<String> getEnabledWorlds() {
        return this.handle.getStringList("enabled-worlds");
    }

    public boolean isHuskHomesIntegrationEnabled() {
        return this.handle.getBoolean("integrations.huskhomes", false);
    }
}