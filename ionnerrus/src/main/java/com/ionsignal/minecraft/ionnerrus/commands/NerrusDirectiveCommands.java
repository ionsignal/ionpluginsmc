package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.directors.AskDirector;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.llm.LLMService;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.entity.Player;

/**
 * Handles natural language LLM directives.
 */
public class NerrusDirectiveCommands {
    @SuppressWarnings("unused")
    private final AgentService agentService;
    private final LLMService llmService;

    public NerrusDirectiveCommands(AgentService agentService, LLMService llmService) {
        this.agentService = agentService;
        this.llmService = llmService;
    }

    public int doDirective(CommandContext<CommandSourceStack> ctx) {
        NerrusAgent agent = ctx.getArgument("agent", NerrusAgent.class);
        String directive = ctx.getArgument("directive", String.class);
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        agent.assignDirective(directive, player);
        player.sendMessage(Component.text("Directive issued to " + agent.getName() + ": '" + directive + "'", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    public int askAgent(CommandContext<CommandSourceStack> ctx) {
        NerrusAgent agent = ctx.getArgument("agent", NerrusAgent.class);
        String question = ctx.getArgument("question", String.class);
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        player.sendMessage(Component.text("Asking " + agent.getName() + ": '" + question + "'", NamedTextColor.GRAY));
        AskDirector askDirector = new AskDirector(llmService);
        askDirector.executeQuery(agent, question, player);
        return Command.SINGLE_SUCCESS;
    }
}