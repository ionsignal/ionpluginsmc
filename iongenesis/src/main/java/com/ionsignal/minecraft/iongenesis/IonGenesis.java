package com.ionsignal.minecraft.iongenesis;

import com.ionsignal.minecraft.iongenesis.command.DebugCommandExecutor;
import com.ionsignal.minecraft.iongenesis.integration.TerraIntegration;

import org.bukkit.plugin.java.JavaPlugin;

public class IonGenesis extends JavaPlugin {
    private static IonGenesis instance;
    private ServiceContainer services;
    private TerraIntegration terraIntegration;

    @Override
    public void onEnable() {
        instance = this;
        // Initialize Services
        try {
            this.services = new ServiceContainer(this);
            this.services.initialize();
        } catch (Exception e) {
            getLogger().severe("Failed to initialize IonGenesis services: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Register Terra Integration
        // We register this immediately to catch config loading events
        try {
            this.terraIntegration = new TerraIntegration(this); // Initialize field
            this.terraIntegration.register();
            getLogger().info("Registered Terra integration listeners.");
        } catch (Exception e) {
            // Critical failure if Terra hook fails, as we cannot function without it
            getLogger().severe("CRITICAL: Failed to register Terra integration: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Register Commands (After integration so platform might be available)
        if (getCommand("iongenesis") != null) {
            getCommand("iongenesis").setExecutor(new DebugCommandExecutor(this));
        }
        getLogger().info("IonGenesis v" + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (services != null) {
            services.shutdown();
        }
        instance = null;
        getLogger().info("IonGenesis disabled.");
    }

    public static IonGenesis getInstance() {
        return instance;
    }

    public ServiceContainer getServices() {
        return services;
    }

    public TerraIntegration getTerraIntegration() {
        return terraIntegration;
    }
}
