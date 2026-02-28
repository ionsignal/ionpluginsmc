package com.ionsignal.minecraft.ioncore.debug;

import java.util.Optional;
import java.util.UUID;

public interface DebugRegistry {
    Optional<DebugSession<?>> getActiveSession(UUID id);

    boolean cancelSession(UUID id);

    <T> DebugSession<T> createSession(UUID id, T initialState, ExecutionController controller);
}