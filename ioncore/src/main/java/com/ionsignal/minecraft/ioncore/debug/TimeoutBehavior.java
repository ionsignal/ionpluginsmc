package com.ionsignal.minecraft.ioncore.debug;

/**
 * Defines how a controller should respond when a pause operation times out.
 */
public enum TimeoutBehavior {
    /**
     * Automatically resume execution when the timeout expires.
     */
    AUTO_RESUME,

    /**
     * Require manual intervention via {@link ExecutionController#resume()}. The session remains paused
     * indefinitely.
     */
    REQUIRE_MANUAL,

    /**
     * Cancel the entire session when the timeout expires, aborting all remaining work.
     */
    CANCEL
}