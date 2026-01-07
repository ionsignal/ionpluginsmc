package com.ionsignal.minecraft.ionnerrus;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistrar;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.bootstrap.NetworkBootstrap;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleService;
import com.ionsignal.minecraft.ionnerrus.compatibility.CraftEngineService;
import com.ionsignal.minecraft.ionnerrus.compatibility.impl.CraftEngineServiceImpl;
import com.ionsignal.minecraft.ionnerrus.network.NetworkBroadcaster;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.hud.HudManager;

import org.bukkit.plugin.Plugin;

/**
 * Central service container managing the lifecycle of all plugin services.
 * Services are instantiated in explicit dependency order and exposed via typed getters.
 */
public class ServiceContainer {
    private final IonNerrus plugin;

    // Core platform services
    private final NerrusManager nerrusManager;

    // Configuration and content
    private final PluginConfig config;
    private final BlockTagManager blockTagManager;
    private final RecipeService recipeService;

    // Goal system
    private final GoalRegistry goalRegistry;
    private final GoalFactory goalFactory;

    // External services
    private final LLMService llmService;
    private final AgentService agentService;
    private final ChatBubbleService chatBubbleService;
    private final HudManager hudManager;
    private final CraftEngineService craftEngineService;

    // Networking
    private final NetworkBroadcaster networkBroadcaster;

    private ServiceContainer(IonNerrus plugin,
            NerrusManager nerrusManager,
            PluginConfig config,
            BlockTagManager blockTagManager,
            RecipeService recipeService,
            GoalRegistry goalRegistry,
            GoalFactory goalFactory,
            LLMService llmService,
            AgentService agentService,
            ChatBubbleService chatBubbleService,
            HudManager hudManager,
            CraftEngineService craftEngineService,
            NetworkBroadcaster networkBroadcaster) {
        this.plugin = plugin;
        this.nerrusManager = nerrusManager;
        this.config = config;
        this.blockTagManager = blockTagManager;
        this.recipeService = recipeService;
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
        this.agentService = agentService;
        this.chatBubbleService = chatBubbleService;
        this.hudManager = hudManager;
        this.craftEngineService = craftEngineService;
        this.networkBroadcaster = networkBroadcaster;
    }

    public static ServiceContainer initialize(IonNerrus plugin) {
        plugin.getLogger().info("Initializing service container...");
        try {
            // Layer 1: Configuration
            PluginConfig config = new PluginConfig(plugin.getConfig());
            // Layer 2: Platform-specific managers
            NerrusManager nerrusManager = new NerrusManager(plugin);
            if (!nerrusManager.initialize()) {
                throw new ServiceInitializationException(
                        "Failed to initialize NerrusManager - incompatible server version?");
            }
            // Layer 3: Content systems
            BlockTagManager blockTagManager = new BlockTagManager();
            RecipeService recipeService = new RecipeService(blockTagManager);
            // Layer 4: Goal system
            GoalRegistry goalRegistry = new GoalRegistry();
            GoalFactory goalFactory = new GoalFactory(blockTagManager, recipeService);
            // Layer 5: External integrations
            LLMService llmService = initializeLLMService(plugin);
            ChatBubbleService chatBubbleService = initializeChatBubbles(plugin);
            HudManager hudManager = initializeHudManager(plugin);
            CraftEngineService craftEngineService = initializeCraftEngine(plugin);
            // Layer 5.5: Network Broadcaster & IonCore Integration
            NetworkBroadcaster networkBroadcaster = null;
            boolean isIonCoreEnabled = plugin.getServer().getPluginManager().isPluginEnabled("IonCore");
            if (isIonCoreEnabled) {
                networkBroadcaster = new NetworkBroadcaster(plugin);
            }
            // Inject CraftEngineService into NerrusManager (Circular dependency resolution)
            nerrusManager.setCraftEngineService(craftEngineService);
            // Layer 6: High-level services
            AgentService agentService = new AgentService(
                    plugin,
                    nerrusManager,
                    goalRegistry,
                    goalFactory,
                    llmService,
                    networkBroadcaster); // Inject Broadcaster (can be null)
            GoalRegistrar goalRegistrar = new GoalRegistrar(goalRegistry, blockTagManager);
            goalRegistrar.registerAll();
            // Layer 7: Network Bootstrap (Wiring)
            if (isIonCoreEnabled) {
                initializeNetworkIntegration(plugin, agentService);
            } else {
                plugin.getLogger().info("IonCore not found. Running in standalone offline mode.");
            }
            plugin.getLogger().info("Service container initialized successfully.");
            return new ServiceContainer(
                    plugin,
                    nerrusManager,
                    config,
                    blockTagManager,
                    recipeService,
                    goalRegistry,
                    goalFactory,
                    llmService,
                    agentService,
                    chatBubbleService,
                    hudManager,
                    craftEngineService,
                    networkBroadcaster);
        } catch (ServiceInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceInitializationException(
                    "Unexpected error during service initialization", e);
        }
    }

    private static void initializeNetworkIntegration(IonNerrus plugin, AgentService agentService) {
        try {
            plugin.getLogger().info("IonCore detected. Initializing Network services...");
            // Bootstrap (Commands from Web)
            NetworkBootstrap netBootstrap = new NetworkBootstrap(plugin, agentService);
            netBootstrap.registerAll();
            plugin.getLogger().info("Network Integration: Listeners paused for Phase 0 migration.");

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize Network Integration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Initializes LLM service (CRITICAL - required for agent cognition).
     */
    private static LLMService initializeLLMService(IonNerrus plugin) {
        try {
            return new LLMService(plugin);
        } catch (Exception e) {
            throw new ServiceInitializationException(
                    "Failed to initialize LLM service - check API configuration", e);
        }
    }

    /**
     * Initializes chat bubble service (NON-CRITICAL - cosmetic feature).
     * Failures are logged but don't prevent plugin startup.
     *
     * @return ChatBubbleService instance, or null if initialization failed.
     */
    private static ChatBubbleService initializeChatBubbles(IonNerrus plugin) {
        try {
            ChatBubbleService service = new ChatBubbleService(plugin);
            plugin.getLogger().info("Chat bubble service initialized.");
            return service;
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "Failed to initialize chat bubble service (non-critical): " + e.getMessage());
            return null;
        }
    }

    /**
     * Initializes the HUD Manager. (NON-CRITICAL - feature).
     * Failures are logged but don't prevent plugin startup.
     */
    private static HudManager initializeHudManager(IonNerrus plugin) {
        // Check if CraftEngine plugin is loaded where Paper (PaperCraftEnginePlugin) and Bukkit
        // (BukkitCraftEnginePlugin) variants register as "CraftEngine" in plugin.yml
        Plugin cePlugin = plugin.getServer()
                .getPluginManager()
                .getPlugin("CraftEngine");
        if (cePlugin == null || !cePlugin.isEnabled()) {
            plugin.getLogger().warning(
                    "CraftEngine not found - HUD features disabled.");
            return null;
        }
        try {
            // Create HudManager instance
            HudManager hudManager = new HudManager(plugin);
            hudManager.finishRegistration();
            // Register as Bukkit event listener for resource pack generation
            // HudManager listens for AsyncResourcePackCacheEvent to inject shaders/fonts
            plugin.getServer().getPluginManager().registerEvents(hudManager, plugin);
            // Save codepoint allocations to disk cache
            // This ensures stable Unicode character assignments across server restarts
            try {
                hudManager.saveCache();
            } catch (java.io.IOException e) {
                plugin.getLogger().warning(
                        "Failed to save HUD codepoint cache (non-fatal): " + e.getMessage());
            }
            // Log successful initialization
            plugin.getLogger().info(String.format(
                    "HUD Manager initialized: %d elements registered, listening for pack generation.",
                    hudManager.getElementCount()));
            return hudManager;
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().warning(
                    "CraftEngine classes not found on classpath - HUD features disabled.");
            return null;
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "Failed to initialize HUD Manager (non-critical): " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Initializes the CraftEngine compatibility layer and uses reflection-based implementation to avoid
     * compile-time dependency requirements.
     */
    private static CraftEngineService initializeCraftEngine(IonNerrus plugin) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("CraftEngine")) {
            try {
                // We use the implementation that utilizes pure reflection
                CraftEngineService service = new CraftEngineServiceImpl();
                plugin.getLogger().info("CraftEngine detected. Compatibility layer initialized.");
                return service;
            } catch (Exception e) {
                plugin.getLogger().warning("CraftEngine detected but failed to initialize compatibility layer: " + e.getMessage());
                e.printStackTrace();
                return new CraftEngineService.NoOp();
            }
        }
        return new CraftEngineService.NoOp();
    }

    public NerrusManager getNerrusManager() {
        return nerrusManager;
    }

    public PluginConfig getConfig() {
        return config;
    }

    public BlockTagManager getBlockTagManager() {
        return blockTagManager;
    }

    public RecipeService getRecipeService() {
        return recipeService;
    }

    public GoalRegistry getGoalRegistry() {
        return goalRegistry;
    }

    public GoalFactory getGoalFactory() {
        return goalFactory;
    }

    public LLMService getLlmService() {
        return llmService;
    }

    public AgentService getAgentService() {
        return agentService;
    }

    public CraftEngineService getCraftEngineService() {
        return craftEngineService;
    }

    /**
     * Gets the chat bubble service. Can return null if FancyHolograms is not available or
     * initialization failed. Callers must check for null before use.
     *
     * @return ChatBubbleService instance, or null if unavailable.
     */
    public ChatBubbleService getChatBubbleService() {
        return chatBubbleService; // NOTE: Can be null
    }

    /**
     * Gets the HUD Manager. Can return null if CraftEngine is not available or
     * initialization failed. Callers must check for null before use.
     *
     * @return HudManager instance, or null if unavailable
     */
    public HudManager getHudManager() {
        return hudManager; // NOTE: Can be null
    }

    public NetworkBroadcaster getNetworkBroadcaster() {
        return networkBroadcaster;
    }

    public boolean isHudAvailable() {
        return hudManager != null;
    }

    /**
     * Shuts down all services in reverse dependency order.
     */
    public void shutdown() {
        plugin.getLogger().info("Shutting down service container...");
        // Layer 6: High-level services first
        if (agentService != null) {
            shutdownService("AgentService", agentService::shutdown);
        }
        // Layer 5: External integrations
        if (llmService != null) {
            shutdownService("LLMService", llmService::shutdown);
        }
        if (chatBubbleService != null) {
            shutdownService("ChatBubbleService", chatBubbleService::cleanup);
        }
        // Layer 4: Goal system (no cleanup needed, but listed for clarity)
        // goalFactory and goalRegistry are stateless
        // ...
        // Layer 3: Content systems (no cleanup needed)
        // recipeService and blockTagManager are stateless
        // ...
        // Layer 2: Platform-specific managers
        if (nerrusManager != null) {
            shutdownService("NerrusManager", nerrusManager::shutdown);
        }
        if (hudManager != null) {
            shutdownService("HudManager", hudManager::shutdown);
        }
        // Layer 1: Configuration (no cleanup needed)
        plugin.getLogger().info("Service container shutdown complete.");
    }

    /**
     * Helper to safely shut down a single service with exception isolation.
     * If one service fails to shut down, others still proceed.
     */
    private void shutdownService(String serviceName, Runnable shutdownAction) {
        try {
            plugin.getLogger().info("Shutting down " + serviceName + "...");
            shutdownAction.run();
        } catch (Exception e) {
            plugin.getLogger().severe("Error shutting down " + serviceName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}