package com.ionsignal.minecraft.ioncore.debug.controllers;

import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;
import com.ionsignal.minecraft.ioncore.debug.SessionStatus;
import com.ionsignal.minecraft.ioncore.debug.TimeoutBehavior;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An execution controller that uses flag-based coordination for main-thread tick loops. Unlike
 * {@link LatchBasedController}, this controller NEVER blocks the calling thread. Instead, it sets
 * an {@code isPaused} flag that consumers must check before processing work.
 * 
 * Thread Safety: All state is managed via atomic types. The {@code mainThreadExecutor} is used
 * for scheduling timeout tasks to ensure they run on the correct thread.
 */
public class TickBasedController implements ExecutionController {
    private final AtomicBoolean isPaused;
    private final AtomicBoolean continueToEnd;
    private final AtomicBoolean cancelled;
    private final Plugin plugin;
    private final TimeoutBehavior timeoutBehavior;
    private final long timeoutMillis;
    private final AtomicReference<DebugSession<?>> session;
    private volatile BukkitTask currentTimeoutTask;

    /**
     * Creates a tick-based controller with timeout behavior.
     *
     * @param plugin
     *            The plugin instance (for accessing Bukkit scheduler).
     * @param timeoutBehavior
     *            How to respond when a pause times out.
     * @param timeoutMillis
     *            Timeout duration in milliseconds (0 = no timeout).
     */
    public TickBasedController(Plugin plugin, TimeoutBehavior timeoutBehavior, long timeoutMillis) {
        this.isPaused = new AtomicBoolean(false);
        this.continueToEnd = new AtomicBoolean(false);
        this.cancelled = new AtomicBoolean(false);
        this.plugin = plugin;
        this.timeoutBehavior = timeoutBehavior;
        this.timeoutMillis = timeoutMillis;
        this.currentTimeoutTask = null;
        this.session = new AtomicReference<>();
    }

    @Override
    public void pause(String phase, String info) {
        // Early exit if already in terminal state
        if (continueToEnd.get() || cancelled.get()) {
            return;
        }
        // Set paused flag (non-blocking)
        isPaused.set(true);
        // Update session status and phase information
        updateSessionStatus(SessionStatus.PAUSED, phase, info);
        // Schedule timeout if configured
        scheduleTimeout();
    }

    @Override
    public void resume() {
        // Clear paused flag
        isPaused.set(false);
        // Cancel any pending timeout
        cancelTimeout();
        // Update session status to ACTIVE
        updateSessionStatus(SessionStatus.ACTIVE, "", "");
    }

    @Override
    public void continueToEnd() {
        continueToEnd.set(true);
        resume(); // Clear any existing pause
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        resume(); // Unblock if paused
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
     * Lifecycle shutdown for the TickBasedController uses Bukkit's scheduler, which is managed by the
     * server and automatically cleaned up. This method cancels any pending timeout task but does not
     * require explicit resource cleanup.
     *
     * This method is idempotent and safe to call multiple times.
     */
    @Override
    public void shutdown() {
        cancelTimeout();
    }

    /**
     * Schedules a timeout task on the main thread executor. The timeout will call
     * {@link #handleTimeout()} after the configured delay.
     */
    private void scheduleTimeout() {
        if (timeoutMillis <= 0) {
            return; // No timeout configured
        }
        // Cancel any existing timeout
        cancelTimeout();
        // Convert milliseconds to ticks (20 ticks per second = 50ms per tick)
        long ticks = timeoutMillis / 50L;
        // Use Bukkit scheduler for non-blocking delayed task execution
        currentTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, this::handleTimeout, ticks);
    }

    /**
     * Cancels the currently scheduled timeout task if one exists.
     */
    private void cancelTimeout() {
        if (currentTimeoutTask != null && !currentTimeoutTask.isCancelled()) {
            currentTimeoutTask.cancel();
        }
        currentTimeoutTask = null;
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
}