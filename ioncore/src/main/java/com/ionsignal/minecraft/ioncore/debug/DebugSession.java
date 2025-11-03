package com.ionsignal.minecraft.ioncore.debug;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A debug session represents a single debugging operation owned by a player. It manages thread-safe
 * access to session state, lifecycle status, and visualization coordination via dirty flags.
 *
 * @param <TState>
 *            The type of state object this session manages (e.g., structure placement state,
 *            pathfinding state).
 */
public class DebugSession<TState> {
    private final UUID owner;
    private final AtomicReference<TState> state;
    private final AtomicReference<SessionStatus> status;
    private final AtomicBoolean visualizationDirty;
    private final AtomicReference<String> currentPhase;
    private final AtomicReference<String> currentInfo;
    private final AtomicReference<ExecutionController> controller;

    /**
     * Creates a new debug session without an execution controller.
     *
     * @param owner
     *            The UUID of the player who owns this session.
     * @param initialState
     *            The initial state object for this session.
     */
    public DebugSession(UUID owner, TState initialState) {
        this(owner, initialState, null);
    }

    /**
     * Creates a new debug session with an optional execution controller.
     *
     * @param owner
     *            The UUID of the player who owns this session.
     * @param initialState
     *            The initial state object for this session.
     * @param controller
     *            The execution controller for this session (may be null).
     */
    public DebugSession(UUID owner, TState initialState, ExecutionController controller) {
        this.owner = owner;
        this.state = new AtomicReference<>(initialState);
        this.status = new AtomicReference<>(SessionStatus.CREATED);
        this.visualizationDirty = new AtomicBoolean(false);
        this.currentPhase = new AtomicReference<>("");
        this.currentInfo = new AtomicReference<>("");
        this.controller = new AtomicReference<>(controller);
        if (controller != null) {
            controller.attachSession(this);
        }
    }

    /**
     * Gets the UUID of the player who owns this session.
     *
     * @return The session owner's UUID.
     */
    public UUID getOwner() {
        return owner;
    }

    /**
     * Gets the current state of this session in a thread-safe manner.
     *
     * @return The current state object.
     */
    public TState getState() {
        return state.get();
    }

    /**
     * Gets the current lifecycle status of this session.
     *
     * @return The current {@link SessionStatus}.
     */
    public SessionStatus getStatus() {
        return status.get();
    }

    /**
     * Checks if this session is currently active or paused (i.e., not terminated).
     *
     * @return {@code true} if status is {@link SessionStatus#ACTIVE} or {@link SessionStatus#PAUSED}.
     */
    public boolean isActive() {
        SessionStatus current = status.get();
        return current == SessionStatus.ACTIVE || current == SessionStatus.PAUSED;
    }

    /**
     * Marks the visualization as dirty, indicating that renderers should update their display on the
     * next tick.
     */
    public void markVisualizationDirty() {
        visualizationDirty.set(true);
    }

    /**
     * Checks if the visualization is currently dirty.
     *
     * @return {@code true} if visualization needs updating.
     */
    public boolean isVisualizationDirty() {
        return visualizationDirty.get();
    }

    /**
     * Clears the dirty flag after visualization has been updated.
     */
    public void clearVisualizationDirty() {
        visualizationDirty.set(false);
    }

    /**
     * Gets the current human-readable phase description.
     *
     * @return The current phase string.
     */
    public String getCurrentPhase() {
        return currentPhase.get();
    }

    /**
     * Gets additional context information for the current phase.
     *
     * @return The current info string.
     */
    public String getCurrentInfo() {
        return currentInfo.get();
    }

    /**
     * Gets the execution controller attached to this session, if any.
     *
     * @return An Optional containing the controller, or empty if none is attached.
     */
    public Optional<ExecutionController> getController() {
        return Optional.ofNullable(controller.get());
    }

    /**
     * Attaches an execution controller to this session. The controller will be linked bidirectionally
     * (controller -> session and session -> controller).
     *
     * @param controller
     *            The controller to attach.
     */
    public void attachController(ExecutionController controller) {
        this.controller.set(controller);
        if (controller != null) {
            controller.attachSession(this);
        }
    }

    /**
     * Transitions the session to a new status with validation. Invalid transitions (e.g., COMPLETED ->
     * ACTIVE) will throw an exception.
     *
     * @param newStatus
     *            The target status.
     * @throws DebugSessionException
     *             if the transition is invalid.
     */
    public void transitionTo(SessionStatus newStatus) {
        SessionStatus currentStatus = status.get();
        if (!isValidTransition(currentStatus, newStatus)) {
            throw new DebugSessionException("Invalid session transition: " + currentStatus + " -> " + newStatus);
        }
        status.set(newStatus);
        markVisualizationDirty();
    }

    /**
     * Checks if a status transition is valid according to the session lifecycle.
     *
     * @param from
     *            The current status.
     * @param to
     *            The target status.
     * @return {@code true} if the transition is allowed.
     */
    private boolean isValidTransition(SessionStatus from, SessionStatus to) {
        // Terminal states cannot transition to non-terminal states
        if ((from == SessionStatus.COMPLETED || from == SessionStatus.CANCELLED || from == SessionStatus.FAILED)
                && (to == SessionStatus.CREATED || to == SessionStatus.ACTIVE || to == SessionStatus.PAUSED)) {
            return false;
        }
        // Allow all other transitions (including same-state transitions)
        return true;
    }

    /**
     * Updates the session status. This method is intended for use by execution controllers and should
     * not be called directly by consumers. Use {@link #transitionTo(SessionStatus)} for validated
     * transitions.
     *
     * @param newStatus
     *            The new status to set.
     */
    public void setStatus(SessionStatus newStatus) {
        status.set(newStatus);
    }

    /**
     * Updates the current phase description. This method is intended for use by execution controllers.
     *
     * @param phase
     *            The new phase description.
     */
    public void setCurrentPhase(String phase) {
        currentPhase.set(phase);
    }

    /**
     * Updates the current info string. This method is intended for use by execution controllers.
     *
     * @param info
     *            The new info string.
     */
    public void setCurrentInfo(String info) {
        currentInfo.set(info);
    }
}