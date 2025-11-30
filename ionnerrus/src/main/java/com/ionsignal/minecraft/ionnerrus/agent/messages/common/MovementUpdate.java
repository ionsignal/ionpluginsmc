package com.ionsignal.minecraft.ionnerrus.agent.messages.common;

import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/**
 * Universal message for movement outcomes (Navigation, Following, Engaging).
 * Wraps a MovementResult.
 */
public record MovementUpdate(
        MovementResult result,
        @Nullable Location target,
        @Nullable Throwable error) {
}