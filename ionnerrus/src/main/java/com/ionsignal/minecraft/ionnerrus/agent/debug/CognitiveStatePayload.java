package com.ionsignal.minecraft.ionnerrus.agent.debug;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A domain-specific DTO representing the internal state of the ReActDirector
 * at the moment of a cognitive breakpoint.
 */
public record CognitiveStatePayload(
        int stepCount,
        @NotNull String directive,
        @Nullable String lastTool,
        @Nullable String pendingSummary) {
}