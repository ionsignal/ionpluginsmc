package com.ionsignal.minecraft.ionnerrus.agent.lifecycle.records;

import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ionnerrus.network.model.Skin;

import org.bukkit.Location;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Strict data model representing a fully-hydrated request to spawn an agent.
 * All core identity fields are required to maintain the Closed Loop architecture.
 */
public record SpawnAgentCommand(
        @NotNull String name,
        @NotNull Location location,
        @Nullable Skin skin,
        @NotNull UUID definitionId,
        @NotNull UUID sessionId,
        @NotNull IonUser owner) {
}