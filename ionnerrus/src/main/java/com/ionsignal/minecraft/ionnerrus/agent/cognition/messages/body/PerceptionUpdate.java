package com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.body;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Universal message for finding/sensing things.
 */
public record PerceptionUpdate(
        boolean found,
        @Nullable Entity targetEntity,
        @Nullable Location targetLocation) {
}