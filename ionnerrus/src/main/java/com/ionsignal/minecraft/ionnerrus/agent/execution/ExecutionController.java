package com.ionsignal.minecraft.ionnerrus.agent.execution;

import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken.Registration;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The authoritative source of truth for an execution lifecycle.
 * Held ONLY by the Agent (or GoalContext).
 */
public class ExecutionController implements AutoCloseable {

    private final ExecutionToken token;
    private final AtomicBoolean active;
    private final ConcurrentLinkedQueue<Runnable> callbacks;

    private Registration parentLinkage;

    private ExecutionController(UUID id) {
        this.token = new ExecutionToken(id, this);
        this.active = new AtomicBoolean(true);
        this.callbacks = new ConcurrentLinkedQueue<>();
    }

    /**
     * Creates a new root execution controller.
     */
    public static ExecutionController create() {
        return new ExecutionController(UUID.randomUUID());
    }

    /**
     * Creates a child controller linked to a parent token.
     * If the parent is cancelled, the child will be cancelled.
     */
    public static ExecutionController createChild(ExecutionToken parent) {
        ExecutionController child = new ExecutionController(UUID.randomUUID());
        // Link child to parent: if parent cancels, child cancels.
        // We store the registration so we can detach (close) later to prevent leaks.
        child.parentLinkage = parent.onCancel(child::cancel);
        // Edge case: If parent was already cancelled before we registered,
        // the callback runs immediately (handled by register logic),
        // but we should ensure child state reflects that.
        if (!parent.isActive()) {
            child.cancel();
        }

        return child;
    }

    /**
     * Idempotent cancellation.
     * Executes all registered callbacks immediately.
     */
    public void cancel() {
        // Atomic transition from true -> false
        if (active.compareAndSet(true, false)) {
            // Drain and execute callbacks
            Runnable callback;
            while ((callback = callbacks.poll()) != null) {
                try {
                    callback.run();
                } catch (Exception e) {
                    // Log but don't stop cancellation of others
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Implements "Add-Then-Check" pattern to prevent race conditions.
     */
    Registration register(Runnable callback) {
        // Add to queue
        callbacks.add(callback);
        // Check if already cancelled
        if (!active.get()) {
            // FIX: Attempt to remove. Only run if WE removed it.
            // If remove returns false, the cancel() thread already took it.
            if (callbacks.remove(callback)) {
                try {
                    callback.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return () -> {
            }; // No-op registration
        }
        // Return handle to remove from queue
        return () -> callbacks.remove(callback);
    }

    public boolean isActive() {
        return active.get();
    }

    public ExecutionToken getToken() {
        return token;
    }

    /**
     * Detaches this controller from its parent (if any).
     * Call this when a Goal completes successfully to prevent memory leaks on the parent token.
     */
    @Override
    public void close() {
        if (parentLinkage != null) {
            parentLinkage.close();
            parentLinkage = null;
        }
    }
}