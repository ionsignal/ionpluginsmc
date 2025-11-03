package com.ionsignal.minecraft.ioncore.debug;

/**
 * Controls execution flow for debug sessions, providing pause/resume/cancel functionality.
 * Implementations determine how
 * execution is blocked (e.g., via latches, callbacks, or tick-based coordination).
 */
public interface ExecutionController {
    /**
     * Pauses execution at a specific phase. The calling thread may block until {@link #resume()} is
     * called, depending on the
     * implementation.
     *
     * @param phase
     *            A human-readable description of the current phase (e.g., "Placing foundation blocks").
     * @param info
     *            Additional context information for this phase (e.g., "Block 5/20").
     */
    void pause(String phase, String info);

    /**
     * Resumes execution after a pause. Unblocks any threads waiting in {@link #pause(String, String)}.
     */
    void resume();

    /**
     * Skips all remaining pause points and allows execution to continue to completion without further
     * interruption.
     */
    void continueToEnd();

    /**
     * Cancels the current execution. Any threads waiting in {@link #pause(String, String)} will be
     * unblocked, and subsequent
     * pause calls will be ignored.
     */
    void cancel();

    /**
     * Checks if execution is currently paused (i.e., a thread is blocked in
     * {@link #pause(String, String)}).
     *
     * @return {@code true} if paused, {@code false} otherwise.
     */
    boolean isPaused();

    /**
     * Checks if the controller is in "continue to end" mode, where all remaining pause points are
     * skipped.
     *
     * @return {@code true} if skipping pauses, {@code false} otherwise.
     */
    boolean isContinuingToEnd();

    /**
     * Checks if execution has been cancelled via {@link #cancel()}.
     *
     * @return {@code true} if cancelled, {@code false} otherwise.
     */
    boolean isCancelled();

    /**
     * Attaches this controller to a debug session for status synchronization. The controller will
     * update the session's status
     * (PAUSED, ACTIVE, etc.) and phase information during execution control operations.
     *
     * @param session
     *            The session to attach.
     */
    void attachSession(DebugSession<?> session);
}