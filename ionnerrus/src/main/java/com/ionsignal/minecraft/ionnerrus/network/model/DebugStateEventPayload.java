package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonEvent;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import java.util.UUID;

import com.fasterxml.jackson.annotation.*;
import org.jetbrains.annotations.Nullable;

/**
 * Outbound event sent to the Web UI when an Agent hits a debug breakpoint.
 */
public record DebugStateEventPayload(
        @JsonProperty("owner") IonUser owner,
        @JsonProperty("sessionId") UUID sessionId,
        @JsonProperty("agentName") String agentName,
        @JsonProperty("stepCount") int stepCount,
        @JsonProperty("directive") String directive,
        @Nullable @JsonProperty("lastTool") String lastTool,
        @Nullable @JsonProperty("pendingSummary") String pendingSummary,
        @JsonProperty("type") String type) implements IonEvent {
}