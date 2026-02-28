package com.ionsignal.minecraft.ioncore.debug;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface VisualizationProvider<T> {
    void render(T state);

    CompletableFuture<Void> cleanup();

    CompletableFuture<Void> cleanup(UUID sessionId);

    Class<T> getStateType();
}