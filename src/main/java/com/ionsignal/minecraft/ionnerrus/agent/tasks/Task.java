package com.ionsignal.minecraft.ionnerrus.agent.tasks;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

import java.util.concurrent.CompletableFuture;

public interface Task {
    CompletableFuture<Void> execute(NerrusAgent agent);

    void cancel();
}