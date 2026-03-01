package com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.body;

import com.ionsignal.minecraft.ionnerrus.persona.components.results.ActionResult;
import org.jetbrains.annotations.Nullable;

/**
 * Universal message for physical actions (break, place, interact).
 */
public record ActionUpdate(
        ActionResult result,
        String actionDescription,
        @Nullable Throwable error) {
}