package com.ionsignal.minecraft.ionnerrus.agent.messages;

import java.util.Optional;

public record TaskCompleted(Optional<Throwable> error) {
}