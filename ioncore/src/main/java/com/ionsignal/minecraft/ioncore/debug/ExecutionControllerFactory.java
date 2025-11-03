package com.ionsignal.minecraft.ioncore.debug;

import com.ionsignal.minecraft.ioncore.debug.controllers.LatchBasedController;

/**
 * Factory for creating {@link ExecutionController} instances with common configurations.
 */
public final class ExecutionControllerFactory {
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    private ExecutionControllerFactory() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Creates a latch-based controller with default timeout behavior (AUTO_RESUME after 30 seconds).
     *
     * @return A new latch-based controller.
     */
    public static LatchBasedController createLatchBased() {
        return new LatchBasedController(TimeoutBehavior.AUTO_RESUME, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Creates a latch-based controller with custom timeout behavior.
     *
     * @param behavior
     *            The timeout behavior strategy.
     * @param timeoutMillis
     *            The timeout duration in milliseconds (0 = no timeout).
     * @return A new latch-based controller.
     */
    public static LatchBasedController createLatchBased(TimeoutBehavior behavior, long timeoutMillis) {
        return new LatchBasedController(behavior, timeoutMillis);
    }

    /**
     * Creates a latch-based controller with no timeout (requires manual resume).
     *
     * @return A new latch-based controller.
     */
    public static LatchBasedController createLatchBasedNoTimeout() {
        return new LatchBasedController(TimeoutBehavior.REQUIRE_MANUAL, 0L);
    }
}