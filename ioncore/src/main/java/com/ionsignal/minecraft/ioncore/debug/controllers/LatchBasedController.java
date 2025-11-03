package com.ionsignal.minecraft.ioncore.debug.controllers;

import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;
import com.ionsignal.minecraft.ioncore.debug.SessionStatus;
import com.ionsignal.minecraft.ioncore.debug.TimeoutBehavior;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An execution controller that blocks async threads using {@link CountDownLatch} while allowing
 * main-thread command handlers to resume execution. Supports configurable timeout behavior for
 * automatic cleanup or manual intervention.
 */
public class LatchBasedController implements ExecutionController {
    private final AtomicReference<CountDownLatch> currentLatch;
    private final AtomicBoolean isPaused;
    private final AtomicBoolean continueToEnd;
    private final AtomicBoolean cancelled;
    private final TimeoutBehavior timeoutBehavior;
    private final long timeoutMillis;
    private final ScheduledExecutorService timeoutScheduler;
    private final AtomicReference<ScheduledFuture<?>> currentTimeoutTask;
    private final AtomicReference<DebugSession<?>> session;

    /**
     * Creates a latch-based controller with timeout behavior.
     *
     * @param timeoutBehavior
     *            How to respond when a pause times out.
     * @param timeoutMillis
     *            Timeout duration in milliseconds (0 = no timeout).
     */
    public LatchBasedController(TimeoutBehavior timeoutBehavior, long timeoutMillis) {
        this.currentLatch = new AtomicReference<>();
        this.isPaused = new AtomicBoolean(false);
        this.continueToEnd = new AtomicBoolean(false);
        this.cancelled = new AtomicBoolean(false);
        this.timeoutBehavior = timeoutBehavior;
        this.timeoutMillis = timeoutMillis;
        this.timeoutScheduler = timeoutMillis > 0 ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "DebugController-Timeout");
            thread.setDaemon(true);
            return thread;
        }) : null;
        this.currentTimeoutTask = new AtomicReference<>();
        this.session = new AtomicReference<>();
    }

    @Override
    public void pause(String phase, String info) {
        if (continueToEnd.get() || cancelled.get()) {
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        currentLatch.set(latch);
        isPaused.set(true);

        DebugSession<?> attachedSession = session.get();
        if (attachedSession != null) {
            attachedSession.setStatus(SessionStatus.PAUSED);
            attachedSession.setCurrentPhase(phase);
            attachedSession.setCurrentInfo(info);
            attachedSession.markVisualizationDirty();
        }

        if (timeoutMillis > 0 && timeoutScheduler != null) {
            ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> handleTimeout(), timeoutMillis, TimeUnit.MILLISECONDS);
            currentTimeoutTask.set(timeoutTask);
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
        } finally {
            isPaused.set(false);
            cancelCurrentTimeoutTask();
        }
    }

    @Override
    public void resume() {
        CountDownLatch latch = currentLatch.getAndSet(null);
        if (latch != null) {
            latch.countDown();
        }
        DebugSession<?> attachedSession = session.get();
        if (attachedSession != null) {
            attachedSession.setStatus(SessionStatus.ACTIVE);
            attachedSession.markVisualizationDirty();
        }
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
        resume();
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
     * Handles timeout expiration according to the configured {@link TimeoutBehavior}.
     */
    private void handleTimeout() {
        if (!isPaused.get()) {
            return;
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
                break;
        }
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
     * Shuts down the timeout scheduler. Should be called when the controller is no longer needed.
     */
    public void shutdown() {
        cancelCurrentTimeoutTask();
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdown();
        }
    }
}