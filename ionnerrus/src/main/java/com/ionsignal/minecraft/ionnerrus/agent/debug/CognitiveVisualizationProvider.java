package com.ionsignal.minecraft.ionnerrus.agent.debug;

import com.ionsignal.minecraft.ioncore.debug.VisualizationProvider;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * Visualization provider for cognitive reasoning debug state that renders LLM conversation progress
 * and tool execution to player actionbar.
 * 
 * Thread Safety: render() must be called on the main server thread (enforced).
 */
public class CognitiveVisualizationProvider implements VisualizationProvider<CognitiveDebugState> {
    @Override
    public void render(CognitiveDebugState state) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "CognitiveVisualizationProvider.render() must be called on main thread");
        }

        Player owner = Bukkit.getPlayer(state.agentId());
        if (owner == null) {
            return; // Player offline
        }

        // Build actionbar showing cognitive progress
        Component actionbar = Component.text()
                .append(Component.text("[Cognition: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(state.agentName(), NamedTextColor.LIGHT_PURPLE))
                .append(Component.text("] Step: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(state.cognitiveStepCount(), NamedTextColor.YELLOW))
                .append(Component.text(" | Tool: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(
                        state.lastToolCall() != null ? state.lastToolCall() : "Thinking",
                        NamedTextColor.AQUA))
                .append(Component.text(" | Conv: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(state.conversationHistory().size(), NamedTextColor.GREEN))
                .build();

        owner.sendActionBar(actionbar);
    }

    @Override
    public Class<CognitiveDebugState> getStateType() {
        return CognitiveDebugState.class;
    }

    @Override
    public CompletableFuture<Void> cleanup() {
        return CompletableFuture.completedFuture(null);
    }
}