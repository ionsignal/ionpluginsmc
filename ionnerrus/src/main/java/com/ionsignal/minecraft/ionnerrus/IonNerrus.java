package com.ionsignal.minecraft.ionnerrus;

import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.bootstrap.CommandRegistrar;
import com.ionsignal.minecraft.ionnerrus.bootstrap.IntegrationBootstrap;
import com.ionsignal.minecraft.ionnerrus.bootstrap.ListenerRegistrar;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleService;
import com.ionsignal.minecraft.ionnerrus.llm.LLMService;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.Level;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

public class IonNerrus extends JavaPlugin {
    private static IonNerrus instance;

    private Executor mainThreadExecutor;
    private Executor offloadThreadExecutor;

    private ServiceContainer services;
    private CommandRegistrar commandRegistrar;
    private ComponentLogger componentLogger;

    private IntegrationBootstrap integrationBootstrap;
    private ListenerRegistrar listenerRegistrar;

    @Override
    public void onEnable() {
        IonNerrus.instance = this;
        enableDebugLogging();
        runClassloaderProbe();
        saveDefaultConfig();
        reloadConfig();

        mainThreadExecutor = runnable -> Bukkit.getScheduler().runTask(this, runnable);
        offloadThreadExecutor = runnable -> getServer().getScheduler().runTaskAsynchronously(this, runnable);

        try {
            services = ServiceContainer.initialize(this);
        } catch (ServiceInitializationException e) {
            getLogger().severe("═══════════════════════════════════════════════════════");
            getLogger().severe("CRITICAL INITIALIZATION FAILURE:");
            getLogger().severe(e.getMessage());
            getLogger().severe("IonNerrus cannot function without these core services.");
            getLogger().severe("Plugin is now disabled. Fix the error and restart the server.");
            getLogger().severe("═══════════════════════════════════════════════════════");
            if (e.getCause() != null) {
                e.getCause().printStackTrace();
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            this.integrationBootstrap = new IntegrationBootstrap(this);
            this.integrationBootstrap.initializeIonCoreIntegration();
        } catch (Exception e) {
            getLogger().warning("Non-critical integration failure: " + e.getMessage());
        }
        try {
            commandRegistrar = new CommandRegistrar(this,
                    services.getAgentService(),
                    services.getBlockTagManager(),
                    services.getGoalFactory(),
                    services.getGoalRegistry(),
                    services.getIdentityService(),
                    services.getPayloadFactory());

            this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                commandRegistrar.registerCommands(event.registrar());
            });
        } catch (Exception e) {
            getLogger().severe("Failed to register commands: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            this.listenerRegistrar = new ListenerRegistrar(
                    this,
                    services.getNerrusManager(),
                    services.getChatBubbleService(),
                    services.getNetworkService());
            this.listenerRegistrar.registerAll();
        } catch (Exception e) {
            getLogger().severe("Failed to register listeners: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("IonNerrus v" + getPluginMeta().getVersion() + " has been enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling IonNerrus v" + getPluginMeta().getVersion() + " plugin.");
        if (services == null) {
            getLogger().warning("Services were never initialized - skipping shutdown.");
            return;
        }

        if (services.getAgentService() != null) {
            getLogger().info("Despawning all agents before shutdown...");
            try {
                services.getAgentService().despawnAll().get(3, TimeUnit.SECONDS);
                getLogger().info("All agents despawned and synced successfully.");
            } catch (Exception e) {
                getLogger().severe("Error during agent despawn: " + e.getMessage());
                e.printStackTrace();
            }
        }

        try {
            if (this.listenerRegistrar != null) {
                this.listenerRegistrar.unregisterAll();
            }
        } catch (Exception e) {
            getLogger().severe("Error unregistering listeners: " + e.getMessage());
            e.printStackTrace();
        }

        try {
            if (this.commandRegistrar != null) {
                this.commandRegistrar.unregisterAll();
            }
        } catch (Exception e) {
            getLogger().severe("Error unregistering commands: " + e.getMessage());
            e.printStackTrace();
        }

        try {
            if (this.integrationBootstrap != null) {
                this.integrationBootstrap.cleanup();
            }
        } catch (Exception e) {
            getLogger().warning("Error cleaning up integrations: " + e.getMessage());
        }

        try {
            services.shutdown();
        } catch (Exception e) {
            getLogger().severe("Error during service container shutdown: " + e.getMessage());
            e.printStackTrace();
        }

        instance = null;
        services = null;
        this.integrationBootstrap = null;
        this.listenerRegistrar = null;
        this.commandRegistrar = null;
        getLogger().info("IonNerrus has been disabled successfully.");
    }

    private void enableDebugLogging() {
        try {
            this.componentLogger = getComponentLogger();
            String pluginLoggerName = this.getName();
            Configurator.setLevel(pluginLoggerName, Level.DEBUG);
            Configurator.setLevel("com.ionsignal.minecraft.ionnerrus", Level.DEBUG);
        } catch (Exception e) {
            getLogger().warning("Failed to enable debug logging: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void runClassloaderProbe() {
        String jacksonVersion = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString();
        getLogger().info("Runtime Jackson Version: " + jacksonVersion);
    }

    public static IonNerrus getInstance() {
        return instance;
    }

    public ComponentLogger getModernLogger() {
        return componentLogger;
    }

    public Executor getOffloadThreadExecutor() {
        return offloadThreadExecutor;
    }

    public Executor getMainThreadExecutor() {
        return mainThreadExecutor;
    }

    public LLMService getLlmService() {
        if (services == null) {
            throw new IllegalStateException("Services not initialized - plugin failed to load");
        }
        return services.getLlmService();
    }

    public AgentService getAgentService() {
        if (services == null) {
            throw new IllegalStateException("Services not initialized - plugin failed to load");
        }
        return services.getAgentService();
    }

    public PluginConfig getPluginConfig() {
        if (services == null) {
            throw new IllegalStateException("Services not initialized - plugin failed to load");
        }
        return services.getConfig();
    }

    public ChatBubbleService getChatBubbleService() {
        if (services == null) {
            throw new IllegalStateException("Services not initialized - plugin failed to load");
        }
        return services.getChatBubbleService();
    }

    public ServiceContainer getServices() {
        return services;
    }
}