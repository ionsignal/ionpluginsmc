package com.ionsignal.minecraft.ionnerrus.agent.messages;

import java.util.Optional;

import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

public record TaskCompleted(Task task, Optional<Throwable> error) {
}