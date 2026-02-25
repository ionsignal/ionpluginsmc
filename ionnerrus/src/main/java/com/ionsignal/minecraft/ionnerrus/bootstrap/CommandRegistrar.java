package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusCloudCommands;
import com.ionsignal.minecraft.ionnerrus.commands.parsers.NerrusAgentParser;
import com.ionsignal.minecraft.ionnerrus.commands.parsers.PersonaDefinitionParser;
import com.ionsignal.minecraft.ionnerrus.network.PayloadFactory;
import com.ionsignal.minecraft.ionnerrus.network.model.PersonaListItem;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import io.leangen.geantyref.TypeToken;

import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.suggestion.Suggestion;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Registers all plugin commands using Cloud Command Framework.
 */
public class CommandRegistrar {
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final BlockTagManager blockTagManager;
    private final GoalFactory goalFactory;
    private final IdentityService identityService;
    private final PayloadFactory payloadFactory;

    private PaperCommandManager<CommandSourceStack> commandManager;

    @SuppressWarnings("UnstableApiUsage")
    public CommandRegistrar(
            IonNerrus plugin,
            AgentService agentService,
            BlockTagManager blockTagManager,
            GoalFactory goalFactory,
            GoalRegistry goalRegistry,
            IdentityService identityService,
            PayloadFactory payloadFactory) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.blockTagManager = blockTagManager;
        this.goalFactory = goalFactory;
        this.identityService = identityService;
        this.payloadFactory = payloadFactory;
    }

    /**
     * Registers all commands.
     */
    public void registerAll() {
        try {
            // Initialize Cloud PaperCommandManager using Builder
            this.commandManager = PaperCommandManager.builder()
                    .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
                    .buildOnEnable(plugin);
            // Register Active Agent Parser
            this.commandManager.parserRegistry().registerParserSupplier(
                    TypeToken.get(NerrusAgent.class),
                    params -> new NerrusAgentParser(agentService));
            this.commandManager.parserRegistry().registerNamedParserSupplier(
                    "nerrus_agent",
                    params -> new NerrusAgentParser(agentService));
            // Register Unspawned Definition Parser
            this.commandManager.parserRegistry().registerParserSupplier(
                    TypeToken.get(PersonaListItem.class),
                    params -> new PersonaDefinitionParser(agentService));
            this.commandManager.parserRegistry().registerNamedParserSupplier(
                    "nerrus_definition",
                    params -> new PersonaDefinitionParser(agentService));
            // Register Suggestion Providers to prevent Tab Completion Degradation
            registerSuggestionProviders();
            // Initialize Annotation Parser with CommandSourceStack
            AnnotationParser<CommandSourceStack> annotationParser = new AnnotationParser<>(
                    commandManager,
                    CommandSourceStack.class);
            // Fetch EventBus and pass it into NerrusCloudCommands
            PostgresEventBus eventBus = IonCore.getInstance().getServiceContainer().getEventBus();
            annotationParser.parse(new NerrusCloudCommands(
                    plugin,
                    agentService,
                    blockTagManager,
                    goalFactory,
                    identityService,
                    eventBus,
                    this.payloadFactory));
            plugin.getLogger().info("Registered commands via Cloud Command Framework.");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize Cloud Command Manager: " + e.getMessage());
            e.printStackTrace();
            // We do not disable the plugin here, but commands will be broken.
        }
    }

    // Helper to wire up domain-specific suggestions
    private void registerSuggestionProviders() {
        // Restored Block Tags (e.g. "logs", "stone")
        this.commandManager.parserRegistry().registerSuggestionProvider(
                "block_tags",
                (ctx, input) -> CompletableFuture.supplyAsync(() -> blockTagManager.getRegisteredGroupNames().stream()
                        .map(Suggestion::suggestion)
                        .collect(Collectors.toList())));
        // Restored Materials (Items only)
        this.commandManager.parserRegistry().registerSuggestionProvider(
                "materials",
                (ctx, input) -> CompletableFuture.supplyAsync(() -> Arrays.stream(Material.values())
                        .filter(Material::isItem)
                        .map(m -> Suggestion.suggestion(m.name().toLowerCase()))
                        .collect(Collectors.toList())));
        // Restored Online Players (Standard Bukkit filtering)
        this.commandManager.parserRegistry().registerSuggestionProvider(
                "online_players",
                (ctx, input) -> CompletableFuture.supplyAsync(() -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .map(Suggestion::suggestion)
                        .collect(Collectors.toList())));
        // Registered Owned Available Personas (Cache)
        this.commandManager.parserRegistry().registerSuggestionProvider(
                "owned_available_personas",
                new PersonaDefinitionParser(agentService));
        // Registered Owned Active Agents
        this.commandManager.parserRegistry().registerSuggestionProvider(
                "owned_active_agents",
                new NerrusAgentParser(agentService));
    }

    /**
     * Unregisters commands.
     */
    public void unregisterAll() {
        if (this.commandManager != null) {
            this.commandManager = null;
        }
        plugin.getLogger().info("Unregistered command manager.");
    }
}