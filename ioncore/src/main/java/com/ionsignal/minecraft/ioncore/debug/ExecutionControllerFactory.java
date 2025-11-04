package com.ionsignal.minecraft.ioncore.debug;

import com.ionsignal.minecraft.ioncore.debug.controllers.CallbackController;
import com.ionsignal.minecraft.ioncore.debug.controllers.LatchBasedController;
import com.ionsignal.minecraft.ioncore.debug.controllers.TickBasedController;

import java.util.concurrent.Executor;

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

    /**
     * Creates a tick-based controller with default timeout behavior (AUTO_RESUME after 30 seconds).
     * Tick-based controllers use flag coordination and never block the calling thread.
     *
     * @param mainThreadExecutor
     *            The main thread executor from the plugin (e.g., Bukkit scheduler wrapper).
     * @return A new tick-based controller.
     */
    public static TickBasedController createTickBased(Executor mainThreadExecutor) {
        return new TickBasedController(mainThreadExecutor, TimeoutBehavior.AUTO_RESUME, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Creates a tick-based controller with custom timeout behavior.
     *
     * @param mainThreadExecutor
     *            The main thread executor from the plugin.
     * @param behavior
     *            The timeout behavior strategy.
     * @param timeoutMillis
     *            The timeout duration in milliseconds (0 = no timeout).
     * @return A new tick-based controller.
     */
    public static TickBasedController createTickBased(Executor mainThreadExecutor, TimeoutBehavior behavior, long timeoutMillis) {
        return new TickBasedController(mainThreadExecutor, behavior, timeoutMillis);
    }

    /**
     * Creates a tick-based controller with no timeout (requires manual resume).
     *
     * @param mainThreadExecutor
     *            The main thread executor from the plugin.
     * @return A new tick-based controller.
     */
    public static TickBasedController createTickBasedNoTimeout(Executor mainThreadExecutor) {
        return new TickBasedController(mainThreadExecutor, TimeoutBehavior.REQUIRE_MANUAL, 0L);
    }

    /**
     * Creates a callback-based controller with default timeout behavior (AUTO_RESUME after 30 seconds).
     * Callback-based controllers return {@link java.util.concurrent.CompletableFuture}s from
     * pauseAsync()
     * for use in async callback chains.
     *
     * @param offloadExecutor
     *            The offload thread executor from the plugin (for async blocking).
     * @return A new callback-based controller.
     */
    public static CallbackController createCallbackBased(Executor offloadExecutor) {
        return new CallbackController(offloadExecutor, TimeoutBehavior.AUTO_RESUME, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Creates a callback-based controller with custom timeout behavior.
     *
     * @param offloadExecutor
     *            The offload thread executor from the plugin.
     * @param behavior
     *            The timeout behavior strategy.
     * @param timeoutMillis
     *            The timeout duration in milliseconds (0 = no timeout).
     * @return A new callback-based controller.
     */
    public static CallbackController createCallbackBased(Executor offloadExecutor, TimeoutBehavior behavior, long timeoutMillis) {
        return new CallbackController(offloadExecutor, behavior, timeoutMillis);
    }

    /**
     * Creates a callback-based controller with no timeout (requires manual resume).
     *
     * @param offloadExecutor
     *            The offload thread executor from the plugin.
     * @return A new callback-based controller.
     */
    public static CallbackController createCallbackBasedNoTimeout(Executor offloadExecutor) {
        return new CallbackController(offloadExecutor, TimeoutBehavior.REQUIRE_MANUAL, 0L);
    }
}