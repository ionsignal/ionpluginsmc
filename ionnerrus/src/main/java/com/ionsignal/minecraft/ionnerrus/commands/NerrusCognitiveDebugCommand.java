package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.DebugSessionRegistry;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;
import com.ionsignal.minecraft.ioncore.debug.ExecutionControllerFactory;
import com.ionsignal.minecraft.ioncore.debug.SessionStatus;
import com.ionsignal.minecraft.ioncore.debug.TimeoutBehavior;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.debug.CognitiveDebugState;

import io.github.sashirestela.openai.domain.chat.ChatMessage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Command interface for cognitive debugging.
 * Provides step-by-step inspection of ReActDirector reasoning.
 */
public class NerrusCognitiveDebugCommand implements CommandExecutor, TabCompleter {
    private final IonNerrus plugin;

    public NerrusCognitiveDebugCommand(IonNerrus plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text(
                    "Usage: /cognitivedebug <start|step|continue|stop|history> <agentName>", NamedTextColor.RED));
            return true;
        }
        String subcommand = args[0].toLowerCase();
        String agentName = args[1];
        NerrusAgent agent = plugin.getAgentService().findAgentByName(agentName);
        if (agent == null) {
            player.sendMessage(Component.text("Agent not found: " + agentName, NamedTextColor.RED));
            return true;
        }
        DebugSessionRegistry registry = IonCore.getDebugRegistry();
        UUID sessionId = agent.getPersona().getUniqueId();
        switch (subcommand) {
            case "start" -> handleStart(player, agent, registry, sessionId);
            case "step" -> handleStep(player, registry, sessionId);
            case "continue" -> handleContinue(player, registry, sessionId);
            case "stop" -> handleStop(player, registry, sessionId);
            case "history" -> handleHistory(player, registry, sessionId);
            default -> player.sendMessage(
                    Component.text("Unknown subcommand. Use: start, step, continue, stop, or history",
                            NamedTextColor.RED));
        }
        return true;
    }

    private void handleStart(Player player, NerrusAgent agent, DebugSessionRegistry registry, UUID sessionId) {
        if (registry.hasActiveSession(sessionId)) {
            player.sendMessage(Component.text(
                    "You already have an active debug session. Use /cognitivedebug stop first.", NamedTextColor.RED));
            return;
        }
        // Create CallbackController with plugin's offload executor
        ExecutionController controller = ExecutionControllerFactory.createCallbackBased(
                plugin.getOffloadThreadExecutor(),
                TimeoutBehavior.REQUIRE_MANUAL, // Cognitive debugging should wait for user
                0L // No timeout
        );
        // Note: Initial state will be null until first cognitiveStep() call
        DebugSession<CognitiveDebugState> session = registry.createSession(
                sessionId,
                null, // State is set on first cognitiveStep()
                controller);
        session.transitionTo(SessionStatus.ACTIVE);
        player.sendMessage(Component.text("Started cognitive debugging for agent: " + agent.getName(), NamedTextColor.GREEN));
        player.sendMessage(Component.text(
                "Give the agent a directive, then use /cognitivedebug step to advance through reasoning.",
                NamedTextColor.GRAY));
    }

    private void handleStep(Player player, DebugSessionRegistry registry, UUID sessionId) {
        registry.getActiveSession(sessionId, CognitiveDebugState.class).ifPresentOrElse(
                session -> {
                    session.getController().ifPresent(ExecutionController::resume);
                    player.sendMessage(Component.text("Stepped cognitive loop forward.", NamedTextColor.GREEN));
                },
                () -> player.sendMessage(Component.text("No active cognitive debug session.", NamedTextColor.RED)));
    }

    private void handleContinue(Player player, DebugSessionRegistry registry, UUID sessionId) {
        registry.getActiveSession(sessionId, CognitiveDebugState.class).ifPresentOrElse(
                session -> {
                    session.getController().ifPresent(ExecutionController::continueToEnd);
                    player.sendMessage(
                            Component.text("Cognitive loop will continue to completion.", NamedTextColor.GREEN));
                },
                () -> player.sendMessage(Component.text("No active cognitive debug session.", NamedTextColor.RED)));
    }

    private void handleStop(Player player, DebugSessionRegistry registry, UUID sessionId) {
        if (registry.cancelSession(sessionId)) {
            player.sendMessage(Component.text("Cognitive debug session stopped.", NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("No active session to stop.", NamedTextColor.RED));
        }
    }

    private void handleHistory(Player player, DebugSessionRegistry registry, UUID sessionId) {
        registry.getActiveSession(sessionId, CognitiveDebugState.class).ifPresentOrElse(
                session -> {
                    CognitiveDebugState state = session.getState();
                    if (state == null) {
                        player.sendMessage(Component.text("No cognitive steps have been taken yet.", NamedTextColor.RED));
                        return;
                    }
                    player.sendMessage(Component.text("=== Conversation History ===", NamedTextColor.GOLD));
                    player.sendMessage(Component.text("Directive: " + state.currentDirective(), NamedTextColor.GRAY));
                    player.sendMessage(Component.text("Steps: " + state.cognitiveStepCount(), NamedTextColor.GRAY));
                    player.sendMessage(
                            Component.text("Last Tool: " + (state.lastToolCall() != null ? state.lastToolCall() : "None"),
                                    NamedTextColor.GRAY));
                    player.sendMessage(Component.text("--- Messages ---", NamedTextColor.YELLOW));
                    int msgNum = 1;
                    for (ChatMessage msg : state.conversationHistory()) {
                        String role = msg.getClass().getSimpleName().replace("Message", "");
                        String content = getMessageContent(msg);
                        if (content.length() > 100) {
                            content = content.substring(0, 100) + "...";
                        }
                        player.sendMessage(Component.text(msgNum + ". " + role + ": " + content, NamedTextColor.WHITE));
                        msgNum++;
                    }
                },
                () -> player.sendMessage(Component.text("No active cognitive debug session.", NamedTextColor.RED)));
    }

    /**
     * Extracts content from different message types.
     */
    private String getMessageContent(ChatMessage msg) {
        return switch (msg) {
            case ChatMessage.SystemMessage sm -> sm.getContent();
            case ChatMessage.UserMessage um -> um.getContent().toString();
            case ChatMessage.AssistantMessage am -> am.getContent().toString();
            case ChatMessage.ToolMessage tm -> tm.getContent();
            case ChatMessage.ResponseMessage rm -> {
                String content = rm.getContent();
                if (content == null || content.isBlank()) {
                    var toolCalls = rm.getToolCalls();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        yield "[Tool Call: " + toolCalls.get(0).getFunction().getName() + "]";
                    }
                    yield "[no content]";
                }
                yield content;
            }
            default -> "[unsupported message type]";
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
            @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("start", "step", "continue", "stop", "history");
        }
        if (args.length == 2) {
            // Suggest agent names for all subcommands
            return plugin.getAgentService().getAgents().stream()
                    .map(NerrusAgent::getName)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}