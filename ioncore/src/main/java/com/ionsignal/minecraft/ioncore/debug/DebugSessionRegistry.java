package com.ionsignal.minecraft.ioncore.debug;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe registry for managing active debug sessions. Each player (UUID) can have at most one
 * active session at a
 * time. The registry handles session lifecycle, ensuring proper cleanup when sessions are removed
 * or cancelled.
 */
public class DebugSessionRegistry {
    private static final Logger LOGGER = Logger.getLogger(DebugSessionRegistry.class.getName());
    private final Map<UUID, DebugSession<?>> sessions = new ConcurrentHashMap<>();
    private final VisualizationProviderRegistry visualizationRegistry;

    /**
     * Creates a registry with the visualization provider registry for cleanup coordination.
     * 
     * @param visualizationRegistry
     *            The registry to look up providers for cleanup.
     */
    public DebugSessionRegistry(VisualizationProviderRegistry visualizationRegistry) {
        this.visualizationRegistry = visualizationRegistry;
    }

    /**
     * Creates and registers a new debug session for a player.
     *
     * @param <TState>
     *            The type of state this session manages.
     * @param owner
     *            The UUID of the player who owns this session.
     * @param initialState
     *            The initial state object for the session.
     * @param controller
     *            The execution controller for the session (may be null).
     * @return The newly created session.
     * @throws DebugSessionException
     *             if the player already has an active session.
     */
    public <TState> DebugSession<TState> createSession(UUID owner, TState initialState, ExecutionController controller) {
        if (sessions.containsKey(owner)) {
            throw new DebugSessionException("Player " + owner + " already has an active debug session. Remove or cancel it first.");
        }
        DebugSession<TState> session = new DebugSession<>(owner, initialState, controller);
        sessions.put(owner, session);
        return session;
    }

    /**
     * Gets the active session for a player, if one exists.
     *
     * @param owner
     *            The UUID of the player.
     * @return An Optional containing the session, or empty if no session exists.
     */
    public Optional<DebugSession<?>> getActiveSession(UUID owner) {
        return Optional.ofNullable(sessions.get(owner));
    }

    /**
     * Gets the active session for a player with type-safe casting.
     *
     * @param <TState>
     *            The expected state type.
     * @param owner
     *            The UUID of the player.
     * @param stateClass
     *            The class of the expected state type (for runtime validation).
     * @return An Optional containing the typed session, or empty if no session exists or type mismatch.
     */
    @SuppressWarnings("unchecked")
    public <TState> Optional<DebugSession<TState>> getActiveSession(UUID owner, Class<TState> stateClass) {
        return Optional.ofNullable(sessions.get(owner)).filter(session -> {
            Object state = session.getState();
            return state == null || stateClass.isInstance(state);
        }).map(session -> (DebugSession<TState>) session);
    }

    /**
     * Checks if a player currently has an active session.
     *
     * @param owner
     *            The UUID of the player.
     * @return {@code true} if an active session exists, {@code false} otherwise.
     */
    public boolean hasActiveSession(UUID owner) {
        return sessions.containsKey(owner);
    }

    /**
     * Removes a session from the registry without cancelling it. The session's status is transitioned
     * to COMPLETED if
     * it is currently active.
     *
     * @param owner
     *            The UUID of the player whose session should be removed.
     * @return {@code true} if a session was removed, {@code false} if no session existed.
     */
    public boolean removeSession(UUID owner) {
        DebugSession<?> session = sessions.remove(owner);
        if (session != null) {
            // Transition to COMPLETED if still active
            if (session.isActive()) {
                session.transitionTo(SessionStatus.COMPLETED);
            }
            shutdownController(session);
            triggerVisualizationCleanup(session);
            return true;
        }
        return false;
    }

    /**
     * Cancels and removes a session from the registry. This method calls the session's controller (if
     * present) to cancel execution, then transitions the status to CANCELLED.
     * 
     * @param owner
     *            The UUID of the player whose session should be cancelled.
     * @return {@code true} if a session was cancelled, {@code false} if no session existed.
     */
    public boolean cancelSession(UUID owner) {
        DebugSession<?> session = sessions.remove(owner); // Remove first (committed)
        if (session == null) {
            return false; // Session doesn't exist
        }
        try {
            // Cancel the controller (should always succeed)
            session.getController().ifPresent(ExecutionController::cancel);
            // Update session status to CANCELLED
            if (session.getStatus() != SessionStatus.CANCELLED) {
                try {
                    session.transitionTo(SessionStatus.CANCELLED);
                } catch (DebugSessionException e) {
                    LOGGER.warning(String.format("Failed to transition session %s to CANCELLED: %s", owner, e.getMessage()));
                    session.setStatus(SessionStatus.CANCELLED);
                }
            }
            shutdownController(session);
            triggerVisualizationCleanup(session);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, String.format("Unexpected error cancelling session %s", owner), e);
            session.getController().ifPresent(controller -> {
                try {
                    controller.cancel();
                } catch (Exception ignored) {
                }
            });
            shutdownController(session);
            triggerVisualizationCleanup(session);
            return true;
        }
    }

    /**
     * Gets all currently active sessions. Returns a snapshot collection; modifications to the
     * collection do not affect
     * the registry.
     *
     * @return A collection of all active sessions.
     */
    public Collection<DebugSession<?>> getAllSessions() {
        return sessions.values();
    }

    /**
     * Removes all sessions from the registry, cancelling any active execution. This method should be
     * called during
     * plugin shutdown.
     */
    public void clear() {
        sessions.values().forEach(session -> {
            session.getController().ifPresent(ExecutionController::cancel);
            if (session.isActive()) {
                session.transitionTo(SessionStatus.CANCELLED);
            }
            shutdownController(session);
            triggerVisualizationCleanup(session);
        });
        sessions.clear();
    }

    /**
     * Gets the number of currently active sessions.
     *
     * @return The session count.
     */
    public int size() {
        return sessions.size();
    }

    private void shutdownController(DebugSession<?> session) {
        session.getController().ifPresent(controller -> {
            try {
                controller.shutdown();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Exception during controller shutdown (non-fatal)", e);
            }
        });
    }

    /**
     * Triggers cleanup on the VisualizationProvider associated with the session's state type.
     */
    @SuppressWarnings("unchecked")
    private void triggerVisualizationCleanup(DebugSession<?> session) {
        if (visualizationRegistry == null || session.getState() == null)
            return;
        // We need to cast to raw DebugStateSnapshot to look up the provider
        Object state = session.getState();
        if (state instanceof DebugStateSnapshot snapshot) {
            Optional<VisualizationProvider<DebugStateSnapshot>> providerOpt = (Optional<VisualizationProvider<DebugStateSnapshot>>) (Optional<?>) visualizationRegistry
                    .get(snapshot.getClass());

            providerOpt.ifPresent(provider -> {
                try {
                    provider.cleanup(session.getOwner());
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error cleaning up visualization for session " + session.getOwner(), e);
                }
            });
        }
    }
}