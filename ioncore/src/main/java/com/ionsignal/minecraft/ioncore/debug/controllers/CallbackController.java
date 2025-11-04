package com.ionsignal.minecraft.ioncore.debug.controllers;

import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;
import com.ionsignal.minecraft.ioncore.debug.SessionStatus;
import com.ionsignal.minecraft.ioncore.debug.TimeoutBehavior;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An execution controller designed for async callback chains (e.g.,
 * {@code CompletableFuture.thenCompose()}).
 * Unlike {@link LatchBasedController}, this controller returns a {@link CompletableFuture} from
 * {@link #pauseAsync(String, String)} that completes when {@link #resume()} is called.
 * 
 * <p>
 * The future's blocking behavior occurs on the offload thread, not the caller's thread, making it
 * safe to use in async callback chains without blocking the main thread.
 * 
 * <p>
 * Typical usage pattern:
 * 
 * <pre>{@code
 * controller.pauseAsync("LLM Request", "Waiting for response...")
 *         .thenCompose(v -> makeAsyncApiCall())
 *         .thenAccept(result -> processResult(result));
 * }</pre>
 * 
 * <p>
 * Thread Safety: All state is managed via atomic types. The {@code offloadExecutor} is used for
 * blocking the pause latch asynchronously.
 */
public class CallbackController implements ExecutionController {
    private final AtomicReference<CountDownLatch> currentLatch;
    private final AtomicBoolean isPaused;
    private final AtomicBoolean continueToEnd;
    private final AtomicBoolean cancelled;
    private final Executor offloadExecutor; // Injected from plugin
    private final TimeoutBehavior timeoutBehavior;
    private final long timeoutMillis;
    private final ScheduledExecutorService timeoutScheduler;
    private final AtomicReference<ScheduledFuture<?>> currentTimeoutTask;
    private final AtomicReference<DebugSession<?>> session;

    /**
     * Creates a callback-based controller with timeout behavior.
     *
     * @param offloadExecutor
     *            The offload thread executor from the plugin (for async blocking).
     * @param timeoutBehavior
     *            How to respond when a pause times out.
     * @param timeoutMillis
     *            Timeout duration in milliseconds (0 = no timeout).
     */
    public CallbackController(Executor offloadExecutor, TimeoutBehavior timeoutBehavior, long timeoutMillis) {
        this.currentLatch = new AtomicReference<>();
        this.isPaused = new AtomicBoolean(false);
        this.continueToEnd = new AtomicBoolean(false);
        this.cancelled = new AtomicBoolean(false);
        this.offloadExecutor = offloadExecutor;
        this.timeoutBehavior = timeoutBehavior;
        this.timeoutMillis = timeoutMillis;
        this.timeoutScheduler = timeoutMillis > 0 ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "CallbackController-Timeout");
            thread.setDaemon(true);
            return thread;
        }) : null;
        this.currentTimeoutTask = new AtomicReference<>();
        this.session = new AtomicReference<>();
    }

    @Override
    public void pause(String phase, String info) {
        throw new UnsupportedOperationException("CallbackController does not support blocking pause(). Use pauseAsync() instead.");
    }

    @Override
    public CompletableFuture<Void> pauseAsync(String phase, String info) {
        // Early exit if already in terminal state
        if (continueToEnd.get() || cancelled.get()) {
            return CompletableFuture.completedFuture(null);
        }

        // Create new latch for this pause
        CountDownLatch latch = new CountDownLatch(1);
        currentLatch.set(latch);
        isPaused.set(true);

        // Update session status
        updateSessionStatus(SessionStatus.PAUSED, phase, info);

        // Schedule timeout if configured
        scheduleTimeout();

        // Return a future that blocks on the offload executor
        return CompletableFuture.runAsync(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled.set(true);
            } finally {
                isPaused.set(false);
                cancelCurrentTimeoutTask();
            }
        }, offloadExecutor);
    }

    @Override
    public void resume() {
        // Count down the latch to unblock waiting futures
        CountDownLatch latch = currentLatch.getAndSet(null);
        if (latch != null) {
            latch.countDown();
        }

        // Update session status
        updateSessionStatus(SessionStatus.ACTIVE, "", "");

        // Cancel any pending timeout
        cancelCurrentTimeoutTask();
    }

    @Override
    public void continueToEnd() {
        continueToEnd.set(true);
        resume();
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        resume(); // Unblock any waiting futures

        // Update session to CANCELLED status
        DebugSession<?> attachedSession = session.get();
        if (attachedSession != null) {
            attachedSession.setStatus(SessionStatus.CANCELLED);
            attachedSession.markVisualizationDirty();
        }
    }

    @Override
    public boolean isPaused() {
        return isPaused.get();
    }

    @Override
    public boolean isContinuingToEnd() {
        return continueToEnd.get();
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void attachSession(DebugSession<?> session) {
        this.session.set(session);
    }

    /**
     * Schedules a timeout task. The timeout will call {@link #handleTimeout()} after the configured
     * delay.
     */
    private void scheduleTimeout() {
        if (timeoutMillis <= 0 || timeoutScheduler == null) {
            return; // No timeout configured
        }

        ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(
                this::handleTimeout,
                timeoutMillis,
                TimeUnit.MILLISECONDS);
        currentTimeoutTask.set(timeoutTask);
    }

    /**
     * Cancels the currently scheduled timeout task if one exists.
     */
    private void cancelCurrentTimeoutTask() {
        ScheduledFuture<?> task = currentTimeoutTask.getAndSet(null);
        if (task != null && !task.isDone()) {
            task.cancel(false);
        }
    }

    /**
     * Handles timeout expiration according to the configured {@link TimeoutBehavior}.
     */
    private void handleTimeout() {
        if (!isPaused.get()) {
            return; // Already resumed, ignore timeout
        }

        switch (timeoutBehavior) {
            case AUTO_RESUME:
                resume();
                break;
            case CANCEL:
                cancel();
                break;
            case REQUIRE_MANUAL:
            default:
                // Do nothing, wait for manual resume
                break;
        }
    }

    /**
     * Updates the attached session's status, phase, and info. Marks visualization as dirty.
     */
    private void updateSessionStatus(SessionStatus newStatus, String phase, String info) {
        DebugSession<?> attachedSession = session.get();
        if (attachedSession != null) {
            attachedSession.setStatus(newStatus);
            attachedSession.setCurrentPhase(phase);
            attachedSession.setCurrentInfo(info);
            attachedSession.markVisualizationDirty();
        }
    }

    /**
     * Shuts down the timeout scheduler. Should be called when the controller is no longer needed.
     */
    public void shutdown() {
        cancelCurrentTimeoutTask();
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdown();
        }
    }
}