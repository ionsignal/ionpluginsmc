package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.network.IonEventBroker;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusAgentCommands;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusDirectiveCommands;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusGoalCommands;
import com.ionsignal.minecraft.ionnerrus.commands.parsers.BlockTagParser;
import com.ionsignal.minecraft.ionnerrus.commands.parsers.NerrusAgentParser;
import com.ionsignal.minecraft.ionnerrus.commands.parsers.PersonaDefinitionParser;
import com.ionsignal.minecraft.ionnerrus.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.network.PayloadFactory;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;

import java.util.List;

/**
 * Constructs and registers the Brigadier command tree for IonNerrus.
 */
public class CommandRegistrar {
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final BlockTagManager blockTagManager;
    private final GoalFactory goalFactory;
    private final IdentityService identityService;
    private final PayloadFactory payloadFactory;

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
     * Builds the Brigadier FSM tree and registers it to Paper.
     * Called from inside the LifecycleEvents.COMMANDS handler.
     */
    public void registerCommands(Commands registrar) {
        IonEventBroker eventBroker = IonCore.getInstance().getServiceContainer().getEventBroker();

        NerrusAgentCommands agentCmds = new NerrusAgentCommands(agentService, identityService, eventBroker, payloadFactory);
        NerrusGoalCommands goalCmds = new NerrusGoalCommands(agentService, goalFactory, blockTagManager, plugin);
        NerrusDirectiveCommands directiveCmds = new NerrusDirectiveCommands(agentService, plugin.getLlmService());

        // Custom Parsers
        NerrusAgentParser agentParser = new NerrusAgentParser(agentService, identityService);
        PersonaDefinitionParser defParser = new PersonaDefinitionParser(agentService);
        BlockTagParser blockTagParser = new BlockTagParser(blockTagManager);

        // Root Node: /nerrus
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("nerrus");

        // /nerrus list
        root.then(Commands.literal("list")
                .requires(ctx -> ctx.getSender().hasPermission("ionnerrus.command.list"))
                .executes(agentCmds::listAgents));

        // /nerrus stop <agent>
        root.then(Commands.literal("stop")
                .requires(ctx -> ctx.getSender().hasPermission("ionnerrus.command.stop"))
                .then(Commands.argument("agent", agentParser)
                        .executes(agentCmds::stopAgent)));

        // /nerrus spawn <definition>
        root.then(Commands.literal("spawn")
                .requires(ctx -> ctx.getSender().hasPermission("ionnerrus.command.spawn"))
                .then(Commands.argument("definition", defParser)
                        .executes(agentCmds::spawnAgent)));

        // /nerrus remove <agent>
        root.then(Commands.literal("remove")
                .requires(ctx -> ctx.getSender().hasPermission("ionnerrus.command.remove"))
                .then(Commands.argument("agent", agentParser)
                        .executes(agentCmds::removeAgent)));

        // /nerrus gather <agent> <block_type> <amount>
        root.then(Commands.literal("gather")
                .requires(ctx -> ctx.getSender().hasPermission("ionnerrus.command.gather"))
                .then(Commands.argument("agent", agentParser)
                        .then(Commands.argument("block_type", blockTagParser)
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400))
                                        .executes(goalCmds::gatherBlock)))));

        // /nerrus give <agent> <target> <item> <amount>
        root.then(Commands.literal("give")
                .requires(ctx -> ctx.getSender().hasPermission("ionnerrus.command.give"))
                .then(Commands.argument("agent", agentParser)
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("item", ArgumentTypes.itemStack())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400))
                                                .executes(goalCmds::giveItem))))));

        // /nerrus follow <agent> <target> [distance]
        root.then(Commands.literal("follow")
                .requires(ctx -> ctx.getSender().hasPermission("ionnerrus.command.follow"))
                .then(Commands.argument("agent", agentParser)
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(ctx -> goalCmds.follow(ctx, 6.0)) // Default distance
                                .then(Commands.argument("distance", DoubleArgumentType.doubleArg(1.0, 100.0))
                                        .executes(ctx -> goalCmds.follow(ctx, ctx.getArgument("distance", Double.class)))))));

        // /nerrus do <agent> <directive>
        root.then(Commands.literal("do")
                .requires(ctx -> ctx.getSender().hasPermission("ionnerrus.command.do"))
                .then(Commands.argument("agent", agentParser)
                        .then(Commands.argument("directive", StringArgumentType.greedyString())
                                .executes(directiveCmds::doDirective))));

        // /nerrus ask <agent> <question>
        root.then(Commands.literal("ask")
                .requires(ctx -> ctx.getSender().hasPermission("ionnerrus.command.ask"))
                .then(Commands.argument("agent", agentParser)
                        .then(Commands.argument("question", StringArgumentType.greedyString())
                                .executes(directiveCmds::askAgent))));

        // Finally, register the constructed tree
        registrar.register(root.build(), "IonNerrus Agent Management", List.of());
        plugin.getLogger().info("Registered commands via Paper Lifecycle API.");
    }

    public void unregisterAll() {
        plugin.getLogger().info("Command unregistration delegated to Paper Lifecycle.");
    }
}