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
import com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState;

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
 * Command interface for agent debugging.
 * Provides step-by-step inspection of agent message processing.
 */
public class NerrusDebugCommand implements CommandExecutor, TabCompleter {
    private final IonNerrus plugin;

    public NerrusDebugCommand(IonNerrus plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /nerrusdebug <start|step|continue|stop|status> <agentName>", NamedTextColor.RED));
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
        UUID ownerId = player.getUniqueId();

        switch (subcommand) {
            case "start" -> handleStart(player, agent, registry, ownerId);
            case "step" -> handleStep(player, registry, ownerId);
            case "continue" -> handleContinue(player, registry, ownerId);
            case "stop" -> handleStop(player, registry, ownerId);
            case "status" -> handleStatus(player, registry, ownerId);
            default -> player
                    .sendMessage(Component.text("Unknown subcommand. Use: start, step, continue, stop, or status", NamedTextColor.RED));
        }

        return true;
    }

    private void handleStart(Player player, NerrusAgent agent, DebugSessionRegistry registry, UUID ownerId) {
        if (registry.hasActiveSession(ownerId)) {
            player.sendMessage(
                    Component.text("You already have an active debug session. Use /nerrusdebug stop first.", NamedTextColor.RED));
            return;
        }

        // Create TickBasedController with plugin's main thread executor
        ExecutionController controller = ExecutionControllerFactory.createTickBased(
                plugin.getMainThreadExecutor(),
                TimeoutBehavior.AUTO_RESUME,
                60_000L // 60 second timeout
        );

        // Create initial state snapshot
        AgentDebugState initialState = AgentDebugState.snapshot(agent);

        // Create and register session
        DebugSession<AgentDebugState> session = registry.createSession(
                ownerId,
                initialState,
                controller);
        session.transitionTo(SessionStatus.ACTIVE);

        player.sendMessage(Component.text("Started debugging agent: " + agent.getName(), NamedTextColor.GREEN));
        player.sendMessage(
                Component.text("Use /nerrusdebug step to advance, /nerrusdebug continue to run to end, /nerrusdebug stop to cancel.",
                        NamedTextColor.GRAY));
    }

    private void handleStep(Player player, DebugSessionRegistry registry, UUID ownerId) {
        registry.getActiveSession(ownerId, AgentDebugState.class).ifPresentOrElse(
                session -> {
                    session.getController().ifPresent(ExecutionController::resume);
                    player.sendMessage(Component.text("Stepped agent forward one message.", NamedTextColor.GREEN));
                },
                () -> player.sendMessage(
                        Component.text("No active debug session. Use /nerrusdebug start <agentName> first.", NamedTextColor.RED)));
    }

    private void handleContinue(Player player, DebugSessionRegistry registry, UUID ownerId) {
        registry.getActiveSession(ownerId, AgentDebugState.class).ifPresentOrElse(
                session -> {
                    session.getController().ifPresent(ExecutionController::continueToEnd);
                    player.sendMessage(Component.text("Agent will continue to completion without pausing.", NamedTextColor.GREEN));
                },
                () -> player.sendMessage(Component.text("No active debug session.", NamedTextColor.RED)));
    }

    private void handleStop(Player player, DebugSessionRegistry registry, UUID ownerId) {
        if (registry.cancelSession(ownerId)) {
            player.sendMessage(Component.text("Debug session stopped.", NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("No active debug session to stop.", NamedTextColor.RED));
        }
    }

    private void handleStatus(Player player, DebugSessionRegistry registry, UUID ownerId) {
        registry.getActiveSession(ownerId, AgentDebugState.class).ifPresentOrElse(
                session -> {
                    AgentDebugState state = session.getState();
                    player.sendMessage(Component.text("=== Agent Debug Status ===", NamedTextColor.GOLD));
                    player.sendMessage(Component.text("Agent: " + state.agentName(), NamedTextColor.WHITE));
                    player.sendMessage(Component.text("Status: " + session.getStatus(), NamedTextColor.YELLOW));
                    player.sendMessage(Component.text(
                            "Current Goal: " + (state.currentGoalName() != null ? state.currentGoalName() : "None"), NamedTextColor.GRAY));
                    player.sendMessage(Component.text(
                            "Current Task: " + (state.currentTaskName() != null ? state.currentTaskName() : "None"), NamedTextColor.GRAY));
                    player.sendMessage(Component.text("Next Message: " + (state.nextMessage() != null ? state.nextMessage() : "None"),
                            NamedTextColor.GRAY));
                    player.sendMessage(Component.text("Phase: " + session.getCurrentPhase(), NamedTextColor.AQUA));
                    player.sendMessage(Component.text("Info: " + session.getCurrentInfo(), NamedTextColor.AQUA));
                },
                () -> player.sendMessage(Component.text("No active debug session.", NamedTextColor.RED)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
            @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("start", "step", "continue", "stop", "status");
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