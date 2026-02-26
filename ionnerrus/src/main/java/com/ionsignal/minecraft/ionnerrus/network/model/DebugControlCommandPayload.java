package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import java.util.UUID;

import com.fasterxml.jackson.annotation.*;

/**
 * Incoming command from the Web UI instructing the Minecraft server to manipulate
 * an active debug session (e.g., Step, Resume, Cancel).
 */
public record DebugControlCommandPayload(
        @JsonProperty("owner") IonUser owner,
        @JsonProperty("sessionId") UUID sessionId,
        @JsonProperty("action") DebugAction action,
        @JsonProperty("type") String type) implements IonCommand {
}