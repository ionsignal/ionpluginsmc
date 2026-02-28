package com.ionsignal.minecraft.ioncore.debug;

public interface ExecutionController {
    void resume();

    boolean isPaused();

    boolean isContinuingToEnd();

    void pause(String reason, String detail);
}