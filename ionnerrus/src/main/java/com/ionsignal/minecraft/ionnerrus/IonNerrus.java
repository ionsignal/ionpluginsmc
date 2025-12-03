package com.ionsignal.minecraft.ionnerrus;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.bootstrap.CommandRegistrar;
import com.ionsignal.minecraft.ionnerrus.bootstrap.IntegrationBootstrap;
import com.ionsignal.minecraft.ionnerrus.bootstrap.ListenerRegistrar;
import com.ionsignal.minecraft.ionnerrus.bootstrap.RecipeModifier;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleService;
import com.ionsignal.minecraft.ionnerrus.hud.HudManager;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executor;

import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.Level;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

public class IonNerrus extends JavaPlugin {
    private static IonNerrus instance;

    private Executor mainThreadExecutor;
    private Executor offloadThreadExecutor;

    // Added service container
    private ServiceContainer services;
    private ComponentLogger componentLogger;

    @Override
    public void onEnable() {
        // Singleton
        IonNerrus.instance = this;
        // Logging level configuration
        enableDebugLogging();
        // Load config before any service initialization
        saveDefaultConfig();
        reloadConfig();
        // Create executors
        mainThreadExecutor = runnable -> Bukkit.getScheduler().runTask(this, runnable);
        offloadThreadExecutor = runnable -> getServer().getScheduler().runTaskAsynchronously(this, runnable);
        // Initialize service container
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
        // External integrations
        try {
            IntegrationBootstrap integrationBootstrap = new IntegrationBootstrap(this);
            integrationBootstrap.initializeIonCoreIntegration();
        } catch (Exception e) {
            getLogger().warning("Non-critical integration failure: " + e.getMessage());
        }
        // Register commands
        try {
            CommandRegistrar commandRegistrar = new CommandRegistrar(
                    this,
                    services.getAgentService(),
                    services.getBlockTagManager(),
                    services.getGoalFactory(),
                    services.getGoalRegistry());
            commandRegistrar.registerAll();
        } catch (Exception e) {
            getLogger().severe("Failed to register commands: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Register event listeners
        try {
            ListenerRegistrar listenerRegistrar = new ListenerRegistrar(
                    this,
                    services.getNerrusManager(),
                    services.getChatBubbleService(),
                    services.getHudManager());
            listenerRegistrar.registerAll();
        } catch (Exception e) {
            getLogger().severe("Failed to register listeners: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Apply recipe modifications
        // Now receives config from container
        try {
            RecipeModifier recipeModifier = new RecipeModifier(this, services.getConfig());
            Bukkit.getScheduler().runTaskLater(this,
                    recipeModifier::disableNonWoodRecipesIfConfigured, 1L);
        } catch (Exception e) {
            getLogger().warning("Recipe modification failed (non-critical): " + e.getMessage());
        }
        //Network Integration
        if (getServer().getPluginManager().isPluginEnabled("IonCore")) {
            try {
                getLogger().info("IonCore detected. Initializing Network services...");
                
                // 1. Input: Bootstrap (Commands from Web)
                com.ionsignal.minecraft.ionnerrus.bootstrap.NetworkBootstrap netBootstrap = 
                    new com.ionsignal.minecraft.ionnerrus.bootstrap.NetworkBootstrap(this, services.getAgentService());
                netBootstrap.registerAll();
                
                // 2. Output: Listener (Events to Web)
                getServer().getPluginManager().registerEvents(
                    new com.ionsignal.minecraft.ionnerrus.bootstrap.NetworkEventListener(), 
                    this
                );
                
            } catch (Exception e) {
                getLogger().severe("Failed to initialize Network Integration: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            getLogger().info("IonCore not found. Running in standalone offline mode.");
        }

        getLogger().info("IonNerrus v" + getPluginMeta().getVersion() + " has been enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling IonNerrus v" + getPluginMeta().getVersion() + " plugin.");
        if (services == null) {
            // Plugin never initialized successfully - nothing to clean up
            getLogger().warning("Services were never initialized - skipping shutdown.");
            return;
        }
        // Despawn agents before the chunk system gets too far into its halt sequence.
        if (services.getAgentService() != null) {
            getLogger().info("Despawning all agents before shutdown...");
            try {
                services.getAgentService().despawnAll();
                getLogger().info("All agents despawned successfully.");
            } catch (Exception e) {
                getLogger().severe("Error during agent despawn: " + e.getMessage());
                e.printStackTrace();
            }
        }
        // Stop recipe modifications (non-critical, best-effort)
        try {
            RecipeModifier recipeModifier = new RecipeModifier(this, services.getConfig());
            recipeModifier.cleanup();
        } catch (Exception e) {
            getLogger().warning("Error during recipe cleanup: " + e.getMessage());
        }
        // Unregister event listeners (critical - prevents NPEs on next events)
        try {
            ListenerRegistrar listenerRegistrar = new ListenerRegistrar(
                    this,
                    services.getNerrusManager(),
                    services.getChatBubbleService(),
                    services.getHudManager());
            listenerRegistrar.unregisterAll();
        } catch (Exception e) {
            getLogger().severe("Error unregistering listeners: " + e.getMessage());
            e.printStackTrace();
        }
        // Unregister commands (critical - prevents command execution after disable)
        try {
            CommandRegistrar commandRegistrar = new CommandRegistrar(
                    this,
                    services.getAgentService(),
                    services.getBlockTagManager(),
                    services.getGoalFactory(),
                    services.getGoalRegistry());
            commandRegistrar.unregisterAll();
        } catch (Exception e) {
            getLogger().severe("Error unregistering commands: " + e.getMessage());
            e.printStackTrace();
        }
        // Clean up external integrations (non-critical, best-effort)
        try {
            IntegrationBootstrap integrationBootstrap = new IntegrationBootstrap(this);
            integrationBootstrap.cleanup();
        } catch (Exception e) {
            getLogger().warning("Error cleaning up integrations: " + e.getMessage());
        }
        // Shut down all core services (critical)
        try {
            services.shutdown();
        } catch (Exception e) {
            getLogger().severe("Error during service container shutdown: " + e.getMessage());
            e.printStackTrace();
        }
        // Give async operations time to complete and conduct a best-effort wait since we can't block
        // onDisable() indefinitely
        try {
            Thread.sleep(500); // 500ms grace period for async cleanup
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Log remaining non-daemon threads to verify cleanup
        if (getLogger().isLoggable(java.util.logging.Level.FINE)) {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] threads = threadBean.dumpAllThreads(false, false);
            long nonDaemonCount = 0;
            for (ThreadInfo thread : threads) {
                Thread t = findThreadById(thread.getThreadId());
                if (t != null && !t.isDaemon() && t.getName().contains("LLM")) {
                    getLogger().fine("Non-daemon thread still alive: " + t.getName());
                    nonDaemonCount++;
                }
            }
            if (nonDaemonCount > 0) {
                getLogger().warning("Found " + nonDaemonCount + " LLM-related non-daemon threads still running");
            }
        }
        // Clear static references to prevent memory leaks (AFTER grace period)
        instance = null;
        services = null;
        getLogger().info("IonNerrus has been disabled successfully.");
    }

    /**
     * Helper method to find a thread by ID (for debugging).
     */
    private Thread findThreadById(long threadId) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.threadId() == threadId) {
                return t;
            }
        }
        return null;
    }

    /**
     * Helper method to enable debug logger from PaperMC
     */
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
        // Null-safe delegation with clear error message
        if (services == null) {
            throw new IllegalStateException("Services not initialized - plugin failed to load");
        }
        return services.getLlmService();
    }

    public AgentService getAgentService() {
        // Null-safe delegation with clear error message
        if (services == null) {
            throw new IllegalStateException("Services not initialized - plugin failed to load");
        }
        return services.getAgentService();
    }

    public PluginConfig getPluginConfig() {
        // Null-safe delegation with clear error message
        if (services == null) {
            throw new IllegalStateException("Services not initialized - plugin failed to load");
        }
        return services.getConfig();
    }

    public ChatBubbleService getChatBubbleService() {
        // Null-safe delegation with clear error message which can still return null even if services are
        // initialized (FancyHolograms may not be available)
        if (services == null) {
            throw new IllegalStateException("Services not initialized - plugin failed to load");
        }
        return services.getChatBubbleService(); // NOTE: Can return null
    }

    /**
     * Gets the HUD Manager if available.
     *
     * This provides access to the HUD rendering system for displaying persistent UI elements (status
     * icons, health bars, goal indicators). The HUD system requires CraftEngine 3.6+ to function.
     *
     * @return HudManager instance, or null if CraftEngine unavailable or initialization failed
     * @throws IllegalStateException
     *             if services not initialized (plugin failed to load)
     */
    public HudManager getHudManager() {
        // Null-safe delegation with clear error message
        if (services == null) {
            throw new IllegalStateException("Services not initialized - plugin failed to load");
        }
        return services.getHudManager(); // NOTE: Can return null (CraftEngine dependency)
    }

    /**
     * Checks if the HUD system is available and ready to use.
     *
     * @return true if HUD features can be used, false otherwise
     */
    public boolean isHudAvailable() {
        // Delegate to ServiceContainer's availability check
        return services != null && services.isHudAvailable();
    }

    // Added getter for container itself
    public ServiceContainer getServices() {
        return services; // Intentionally allow null check by callers
    }
}