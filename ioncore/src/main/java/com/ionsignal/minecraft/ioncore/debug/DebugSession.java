package com.ionsignal.minecraft.ioncore.debug;

import java.util.Optional;

public interface DebugSession<T> {
    SessionStatus getStatus();

    void setStatus(SessionStatus status);

    boolean isActive();

    Optional<ExecutionController> getController();

    void transitionTo(SessionStatus status);

    void setState(T state);
}