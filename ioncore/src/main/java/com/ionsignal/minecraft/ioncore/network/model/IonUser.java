package com.ionsignal.minecraft.ioncore.network.model;

import java.util.UUID;

/**
 * Represents a fully linked Platform Account.
 */
public record IonUser(UUID id, MinecraftIdentity identity) {
    // System user constant for administrative/debug actions or fallback scenarios
    public static final IonUser SYSTEM = new IonUser(
            new UUID(0L, 0L),
            new MinecraftIdentity(new UUID(0L, 0L), "System"));
}