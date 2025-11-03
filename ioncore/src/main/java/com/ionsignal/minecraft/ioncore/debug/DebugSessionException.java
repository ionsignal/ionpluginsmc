package com.ionsignal.minecraft.ioncore.debug;

/**
 * Exception thrown when session operations fail due to invalid state or duplicate sessions.
 */
public class DebugSessionException extends RuntimeException {
    public DebugSessionException(String message) {
        super(message);
    }

    public DebugSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}