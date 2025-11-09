package com.ionsignal.minecraft.ionnerrus.agent.debug;

import com.ionsignal.minecraft.ioncore.debug.VisualizationProvider;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Visualization provider for agent execution state.
 * Renders agent execution information via actionbar updates and optional path visualization.
 * 
 * Thread Safety: render() must be called on the main server thread (enforced).
 */
public class AgentVisualizationProvider implements VisualizationProvider<AgentDebugState> {
    private static final Logger LOGGER = Logger.getLogger(AgentVisualizationProvider.class.getName());

    @Override
    public void render(AgentDebugState state) {
        // Enforce main thread execution
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "AgentVisualizationProvider.render() must be called on main thread");
        }
        Player owner = Bukkit.getPlayer(state.agentId());
        if (owner == null) {
            return; // Player offline, nothing to render
        }
        // Build actionbar showing current agent state
        Component actionbar = Component.text()
                .append(Component.text("[Agent: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(state.agentName(), NamedTextColor.YELLOW))
                .append(Component.text("] Goal: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(
                        state.currentGoalName() != null ? state.currentGoalName() : "None",
                        NamedTextColor.BLUE))
                .append(Component.text(" | Task: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(
                        state.currentTaskName() != null ? state.currentTaskName() : "None",
                        NamedTextColor.GREEN))
                .append(Component.text(" | Queue: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.valueOf(state.goalMailboxSize()), NamedTextColor.AQUA))
                .build();
        owner.sendActionBar(actionbar);
        // Optionally visualize current navigation path
        if (state.currentPath() != null && !state.currentPath().isEmpty()) {
            DebugVisualizer.displayPath(state.currentPath(), 20); // 1 second display
        }
    }

    @Override
    public Class<AgentDebugState> getStateType() {
        return AgentDebugState.class;
    }

    /**
     * Optional cleanup implementation (no active resources to cleanup)
     */
    @Override
    public CompletableFuture<Void> cleanup() {
        return CompletableFuture.completedFuture(null);
    }
}