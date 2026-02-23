package com.ionsignal.minecraft.ioncore.network.model;

import java.util.UUID;

/**
 * Represents a raw connection to the Game Server.
 */
public record MinecraftIdentity(UUID uuid, String username) {
}