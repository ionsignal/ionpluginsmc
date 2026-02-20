package com.ionsignal.minecraft.ioncore.network.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Represents a raw connection to the Game Server.
 * Maps to 'MinecraftIdentitySchema' in IonControl.
 */
public record MinecraftIdentity(
        @JsonProperty("uuid") UUID uuid,
        @JsonProperty("username") String username) {
}