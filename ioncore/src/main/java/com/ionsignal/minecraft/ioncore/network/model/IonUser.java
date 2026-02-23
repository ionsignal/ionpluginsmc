package com.ionsignal.minecraft.ioncore.network.model;

import java.util.UUID;

/**
 * Represents a fully linked Platform Account.
 */
public record IonUser(UUID id, MinecraftIdentity identity) {
}