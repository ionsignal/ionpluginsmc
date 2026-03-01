package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusAgentCommands;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusDirectiveCommands;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusGoalCommands;
import com.ionsignal.minecraft.ionnerrus.commands.parsers.NerrusAgentParser;
import com.ionsignal.minecraft.ionnerrus.commands.parsers.PersonaDefinitionParser;
import com.ionsignal.minecraft.ionnerrus.content.BlockTagManager;
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
            // Fetch EventBus for commands that require network dispatch
            PostgresEventBus eventBus = IonCore.getInstance().getServiceContainer().getEventBus();
            annotationParser.parse(new NerrusAgentCommands(
                    agentService, identityService, eventBus, payloadFactory));

            annotationParser.parse(new NerrusGoalCommands(
                    agentService, goalFactory, blockTagManager, plugin));

            annotationParser.parse(new NerrusDirectiveCommands(
                    agentService, plugin.getLlmService()));
            plugin.getLogger().info("Registered commands via Cloud Command Framework.");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize Cloud Command Manager: " + e.getMessage());
            e.printStackTrace();
            // We do not disable the plugin here, but commands will be broken.
        }
    }

    private void registerSuggestionProviders() {
        this.commandManager.parserRegistry().registerSuggestionProvider(
                "block_tags",
                (ctx, input) -> CompletableFuture.supplyAsync(() -> blockTagManager.getRegisteredGroupNames().stream()
                        .map(Suggestion::suggestion)
                        .collect(Collectors.toList())));
        this.commandManager.parserRegistry().registerSuggestionProvider(
                "materials",
                (ctx, input) -> CompletableFuture.supplyAsync(() -> Arrays.stream(Material.values())
                        .filter(Material::isItem)
                        .map(m -> Suggestion.suggestion(m.name().toLowerCase()))
                        .collect(Collectors.toList())));
        this.commandManager.parserRegistry().registerSuggestionProvider(
                "online_players",
                (ctx, input) -> CompletableFuture.supplyAsync(() -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .map(Suggestion::suggestion)
                        .collect(Collectors.toList())));
        this.commandManager.parserRegistry().registerSuggestionProvider(
                "owned_available_personas",
                new PersonaDefinitionParser(agentService));
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