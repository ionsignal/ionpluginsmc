package com.ionsignal.minecraft.ioncore.exceptions;

/**
 * Thrown when a critical service fails to initialize during plugin startup, note that non-critical
 * failures should be logged as warnings, not thrown as exceptions.
 */
public class ServiceInitializationException extends RuntimeException {
    public ServiceInitializationException(String message) {
        super(message);
    }

    public ServiceInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}