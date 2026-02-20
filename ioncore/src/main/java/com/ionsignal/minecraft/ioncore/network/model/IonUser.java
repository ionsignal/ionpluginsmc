package com.ionsignal.minecraft.ioncore.network.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Represents a fully linked Platform Account.
 * Maps to 'IonUserSchema' in IonControl.
 */
public record IonUser(
        @JsonProperty("id") UUID id,
        @JsonProperty("identity") MinecraftIdentity identity) {
}