package com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.system;

import java.util.Optional;

import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Task;

public record TaskCompleted(Task task, Optional<Throwable> error) {
}