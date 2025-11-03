package com.ionsignal.minecraft.ioncore.debug;

/**
 * Lifecycle states for a debug session.
 */
public enum SessionStatus {
    /**
     * Session has been initialized but not yet started.
     */
    CREATED,

    /**
     * Session is actively executing.
     */
    ACTIVE,

    /**
     * Session is paused, waiting for user input or external event.
     */
    PAUSED,

    /**
     * Session has finished successfully.
     */
    COMPLETED,

    /**
     * Session was aborted by user request or timeout.
     */
    CANCELLED,

    /**
     * Session terminated due to an error.
     */
    FAILED
}