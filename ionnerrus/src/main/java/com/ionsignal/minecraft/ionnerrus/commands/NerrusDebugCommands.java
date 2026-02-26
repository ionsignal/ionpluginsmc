package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugService;
import com.ionsignal.minecraft.ionnerrus.network.model.DebugAction;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.command.CommandSender;

import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.UUID;

/**
 * Handles local debug session management and control actions.
 */
public class NerrusDebugCommands {
    @SuppressWarnings("unused")
    private final AgentService agentService;
    private final AgentDebugService agentDebugService;

    public NerrusDebugCommands(AgentService agentService, AgentDebugService agentDebugService) {
        this.agentService = agentService;
        this.agentDebugService = agentDebugService;
    }

    @Command("nerrus debug <agent>")
    @Permission("ionnerrus.command.debug")
    public void toggleDebug(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent) {
        CommandSender sender = stack.getSender();
        boolean enabled = agentDebugService.toggleDebugSession(agent);
        if (enabled) {
            sender.sendMessage(Component.text(
                    "[Debug] Cognitive debug session ENABLED for " + agent.getName() + ". Waiting for next cognitive step...",
                    NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text(
                    "[Debug] Cognitive debug session DISABLED for " + agent.getName() + ". Agent will resume normally.",
                    NamedTextColor.YELLOW));
        }
    }

    @Command("nerrus debug step <agent>")
    @Permission("ionnerrus.command.debug")
    public void stepDebug(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent) {
        CommandSender sender = stack.getSender();
        UUID sessionId = agent.getPersona().getUniqueId();
        boolean success = agentDebugService.executeDebugAction(sessionId, DebugAction.STEP);
        if (success) {
            sender.sendMessage(Component.text("[Debug] Step issued to " + agent.getName() + ".", NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("[Debug] No active debug session found for " + agent.getName() + ".", NamedTextColor.RED));
        }
    }

    @Command("nerrus debug resume <agent>")
    @Permission("ionnerrus.command.debug")
    public void resumeDebug(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent) {
        CommandSender sender = stack.getSender();
        UUID sessionId = agent.getPersona().getUniqueId();
        boolean success = agentDebugService.executeDebugAction(sessionId, DebugAction.RESUME);
        if (success) {
            sender.sendMessage(Component.text("[Debug] Resume issued to " + agent.getName() + ".", NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("[Debug] No active debug session found for " + agent.getName() + ".", NamedTextColor.RED));
        }
    }

    @Command("nerrus debug continue <agent>")
    @Permission("ionnerrus.command.debug")
    public void continueDebug(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent) {
        CommandSender sender = stack.getSender();
        UUID sessionId = agent.getPersona().getUniqueId();
        boolean success = agentDebugService.executeDebugAction(sessionId, DebugAction.CONTINUE);
        if (success) {
            sender.sendMessage(Component.text(
                    "[Debug] " + agent.getName() + " will now run to completion without further pauses.",
                    NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("[Debug] No active debug session found for " + agent.getName() + ".", NamedTextColor.RED));
        }
    }

    @Command("nerrus debug cancel <agent>")
    @Permission("ionnerrus.command.debug")
    public void cancelDebug(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent) {
        CommandSender sender = stack.getSender();
        UUID sessionId = agent.getPersona().getUniqueId();
        boolean success = agentDebugService.executeDebugAction(sessionId, DebugAction.CANCEL);
        if (success) {
            sender.sendMessage(Component.text("[Debug] Debug session cancelled for " + agent.getName() + ".", NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("[Debug] No active debug session found for " + agent.getName() + ".", NamedTextColor.RED));
        }
    }
}