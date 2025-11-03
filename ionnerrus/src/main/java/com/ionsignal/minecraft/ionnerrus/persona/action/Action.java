package com.ionsignal.minecraft.ionnerrus.persona.action;

import java.util.concurrent.CompletableFuture;

import com.ionsignal.minecraft.ionnerrus.persona.Persona;

public interface Action {
    void start(Persona persona);

    void tick();

    void stop();

    ActionStatus getStatus();

    CompletableFuture<ActionStatus> getFuture();
}