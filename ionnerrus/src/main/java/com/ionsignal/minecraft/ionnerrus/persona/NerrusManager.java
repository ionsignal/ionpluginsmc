package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.compatibility.CraftEngineService;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinCache;
import com.ionsignal.minecraft.ionnerrus.util.ServerVersion;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

public class NerrusManager {
    private static NerrusManager instance;
    private final IonNerrus plugin;
    private final NerrusRegistry registry;
    private NerrusTick tickTask;
    private SkinCache skinCache;

    // Default to NoOp until injected by ServiceContainer
    private CraftEngineService craftEngineService = new CraftEngineService.NoOp();

    public NerrusManager(IonNerrus plugin) {
        instance = this;
        this.plugin = plugin;
        this.registry = new NerrusRegistry(this);
        this.skinCache = new SkinCache(plugin);
    }

    public boolean initialize() {
        String minecraftVersion = ServerVersion.getMinecraftVersion();
        if ("1.21.8".equals(minecraftVersion)) {
            getLogger().info("Initialized Persona management for Minecraft version " + minecraftVersion);
        } else {
            getLogger().severe(
                    "Unsupported server version: " + minecraftVersion
                            + ". This version of IonNerrus requires Minecraft 1.21.7 or above. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(plugin);
            return false;
        }
        tickTask = new NerrusTick(this);
        tickTask.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (skinCache != null) {
            skinCache.shutdown();
        }
        registry.clear();
        instance = null;
    }

    public void setCraftEngineService(CraftEngineService craftEngineService) {
        this.craftEngineService = craftEngineService;
    }

    public CraftEngineService getCraftEngineService() {
        return craftEngineService;
    }

    public static NerrusManager getInstance() {
        return instance;
    }

    public NerrusRegistry getRegistry() {
        return registry;
    }

    public IonNerrus getPlugin() {
        return plugin;
    }

    public Logger getLogger() {
        return plugin.getLogger();
    }

    public SkinCache getSkinCache() {
        return skinCache;
    }
}