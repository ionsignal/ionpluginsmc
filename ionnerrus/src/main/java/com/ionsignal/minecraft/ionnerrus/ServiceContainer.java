package com.ionsignal.minecraft.ionnerrus;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalRegistrar;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.network.NetworkService;
import com.ionsignal.minecraft.ionnerrus.network.PayloadFactory;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleService;
import com.ionsignal.minecraft.ionnerrus.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutorService;

/**
 * Central service container managing the lifecycle of all plugin services where they are
 * instantiated in explicit dependency order and exposed via typed getters.
 */
public class ServiceContainer {
    private final IonNerrus plugin;

    // Core platform services
    private final NerrusManager nerrusManager;

    // Configuration and content
    private final PluginConfig config;
    private final BlockTagManager blockTagManager;

    // Goal system
    private final GoalRegistry goalRegistry;
    private final GoalFactory goalFactory;

    // External services
    private final LLMService llmService;
    private final AgentService agentService;
    private final ChatBubbleService chatBubbleService;
    private final IdentityService identityService;

    // PayloadFactory service
    private final PayloadFactory payloadFactory;

    // Networking
    @Nullable
    private final NetworkService networkService;

    private ServiceContainer(IonNerrus plugin,
            NerrusManager nerrusManager,
            PluginConfig config,
            BlockTagManager blockTagManager,
            GoalRegistry goalRegistry,
            GoalFactory goalFactory,
            LLMService llmService,
            AgentService agentService,
            ChatBubbleService chatBubbleService,
            IdentityService identityService,
            PayloadFactory payloadFactory,
            @Nullable NetworkService nerrusBridge) {
        this.plugin = plugin;
        this.nerrusManager = nerrusManager;
        this.config = config;
        this.blockTagManager = blockTagManager;
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
        this.agentService = agentService;
        this.chatBubbleService = chatBubbleService;
        this.identityService = identityService;
        this.payloadFactory = payloadFactory;
        this.networkService = nerrusBridge;
    }

    public static ServiceContainer initialize(IonNerrus plugin) {
        plugin.getLogger().info("Initializing service container...");
        // Ensure IonCore is actually running and healthy before proceeding.
        if (!plugin.getServer().getPluginManager().isPluginEnabled("IonCore")) {
            throw new ServiceInitializationException(
                    "CRITICAL: IonCore is missing or failed to enable. IonNerrus cannot start.");
        }
        try {
            // Retrieve EventBus early for injection
            var coreContainer = com.ionsignal.minecraft.ioncore.IonCore.getInstance().getServiceContainer();
            // Layer 1: Configuration
            var config = new PluginConfig(plugin.getConfig());
            // Layer 2: Platform-specific managers
            var nerrusManager = new NerrusManager(plugin);
            if (!nerrusManager.initialize()) {
                throw new ServiceInitializationException(
                        "Failed to initialize NerrusManager - incompatible server version?");
            }
            // Layer 3: Content systems
            var blockTagManager = new BlockTagManager();
            // Layer 4: Goal system
            var goalRegistry = new GoalRegistry();
            var goalFactory = new GoalFactory(blockTagManager);
            // Layer 5: External integrations
            var llmService = initializeLLMService(plugin);
            var chatBubbleService = initializeChatBubbles(plugin);
            var identityService = coreContainer.getIdentityService();
            // Layer 5.5: Retrieve Identity Service and JsonService from IonCore
            var payloadFactory = new PayloadFactory(coreContainer.getJsonService());
            // Layer 6: High-level services
            AgentService agentService = new AgentService(
                    plugin,
                    nerrusManager,
                    goalRegistry,
                    goalFactory,
                    llmService);
            GoalRegistrar goalRegistrar = new GoalRegistrar(goalRegistry, blockTagManager);
            goalRegistrar.registerAll();
            // Layer 7: Network Bootstrap (Wiring)
            NetworkService nerrusBridge = null;
            boolean isIonCoreEnabled = plugin.getServer().getPluginManager().isPluginEnabled("IonCore");
            if (isIonCoreEnabled) {
                nerrusBridge = initializeNetworking(plugin, agentService, payloadFactory);
            } else {
                plugin.getLogger().info("IonCore not found. Running in standalone offline mode.");
            }
            plugin.getLogger().info("Service container initialized successfully.");
            return new ServiceContainer(
                    plugin,
                    nerrusManager,
                    config,
                    blockTagManager,
                    goalRegistry,
                    goalFactory,
                    llmService,
                    agentService,
                    chatBubbleService,
                    identityService,
                    payloadFactory,
                    nerrusBridge);
        } catch (ServiceInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceInitializationException(
                    "Unexpected error during service initialization", e);
        }
    }

    private static @Nullable NetworkService initializeNetworking(IonNerrus plugin, AgentService agentService,
            PayloadFactory payloadFactory) {
        try {
            plugin.getLogger().info("IonCore detected. Initializing Network services...");
            var coreContainer = com.ionsignal.minecraft.ioncore.IonCore.getInstance().getServiceContainer();
            var eventBroker = coreContainer.getEventBroker();
            var commandRegistrar = eventBroker.getCommandRegistrar();
            var jsonService = coreContainer.getJsonService();
            ExecutorService virtualThreadExecutor = coreContainer.getVirtualThreadExecutor();
            var bridge = new NetworkService(
                    plugin,
                    agentService,
                    eventBroker,
                    commandRegistrar,
                    jsonService,
                    payloadFactory,
                    virtualThreadExecutor);
            plugin.getLogger().info("Network Integration: NerrusBridge initialized.");
            return bridge;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize Network Integration: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static LLMService initializeLLMService(IonNerrus plugin) {
        try {
            return new LLMService(plugin);
        } catch (Exception e) {
            throw new ServiceInitializationException(
                    "Failed to initialize LLM service - check API configuration", e);
        }
    }

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

    public NerrusManager getNerrusManager() {
        return nerrusManager;
    }

    public PluginConfig getConfig() {
        return config;
    }

    public BlockTagManager getBlockTagManager() {
        return blockTagManager;
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

    public ChatBubbleService getChatBubbleService() {
        return chatBubbleService; // NOTE: Can be null
    }

    public IdentityService getIdentityService() {
        return identityService;
    }

    public PayloadFactory getPayloadFactory() {
        if (payloadFactory == null) {
            throw new IllegalStateException("PayloadFactory not initialized");
        }
        return payloadFactory;
    }

    @Nullable
    public NetworkService getNetworkService() {
        return networkService;
    }

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
        // blockTagManager is stateless
        // ...
        // Layer 2: Platform-specific managers
        if (nerrusManager != null) {
            shutdownService("NerrusManager", nerrusManager::shutdown);
        }
        // Layer 1: Configuration (no cleanup needed)
        // NerrusBridge has no shutdown — its Bukkit listener is cleared by
        // ListenerRegistrar.unregisterAll()
        // and its command handlers are cleared by PostgresEventBus.shutdown() in IonCore.
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