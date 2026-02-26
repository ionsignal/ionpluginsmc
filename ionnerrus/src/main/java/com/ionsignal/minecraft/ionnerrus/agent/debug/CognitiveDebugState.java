package com.ionsignal.minecraft.ionnerrus.agent.debug;

import com.ionsignal.minecraft.ioncore.debug.DebugStateSnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Immutable snapshot of an agent's cognitive state.
 * Includes the agentId to allow the renderer to locate the physical entity.
 */
public record CognitiveDebugState(
        @NotNull UUID agentId,
        int stepCount,
        @NotNull String directive,
        @Nullable String lastTool,
        @Nullable String pendingSummary) implements DebugStateSnapshot {

    @Override
    public @NotNull String getDebugLabel() {
        return "Cognitive Reasoning";
    }
}