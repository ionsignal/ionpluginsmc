package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.agent.llm.directors.AskDirector;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.entity.Player;

import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

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

    @Command("nerrus do <agent> <directive>")
    @Permission("ionnerrus.command.do")
    public void doDirective(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent,
            @Argument("directive") @Greedy String directive) {
        if (!(stack.getExecutor() instanceof Player player)) {
            stack.getSender().sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
            return;
        }
        agent.assignDirective(directive, player);
        player.sendMessage(Component.text("Directive issued to " + agent.getName() + ": '" + directive + "'", NamedTextColor.GREEN));
    }

    @Command("nerrus ask <agent> <question>")
    @Permission("ionnerrus.command.ask")
    public void askAgent(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent,
            @Argument("question") @Greedy String question) {
        if (!(stack.getExecutor() instanceof Player player)) {
            stack.getSender().sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("Asking " + agent.getName() + ": '" + question + "'", NamedTextColor.GRAY));
        AskDirector askDirector = new AskDirector(llmService);
        askDirector.executeQuery(agent, question, player);
    }
}