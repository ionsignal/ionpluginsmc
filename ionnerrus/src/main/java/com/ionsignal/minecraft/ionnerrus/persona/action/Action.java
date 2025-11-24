package com.ionsignal.minecraft.ionnerrus.persona.action;

import java.util.concurrent.CompletableFuture;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;

public interface Action {
    void start(PersonaEntity personaEntity);

    void tick();

    void stop();

    ActionStatus getStatus();

    CompletableFuture<ActionStatus> getFuture();
}