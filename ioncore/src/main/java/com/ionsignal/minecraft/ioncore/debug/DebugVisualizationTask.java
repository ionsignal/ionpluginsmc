package com.ionsignal.minecraft.ioncore.debug;

import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This task runs once per tick and coordinates visualization updates for all sessions being the
 * single source of truth for triggering debug renders, preventing race conditions and ensuring all
 * renders happen safely on the main server thread.
 */
public class DebugVisualizationTask extends BukkitRunnable {
    private static final Logger LOGGER = Logger.getLogger(DebugVisualizationTask.class.getName());

    private final DebugSessionRegistry sessionRegistry;
    private final VisualizationProviderRegistry visualizationRegistry;

    /**
     * Creates a visualization task with access to registries.
     *
     * @param sessionRegistry
     *            The session registry from IonCore
     * @param visualizationRegistry
     *            The visualization provider registry from IonCore
     */
    public DebugVisualizationTask(
            DebugSessionRegistry sessionRegistry,
            VisualizationProviderRegistry visualizationRegistry) {
        this.sessionRegistry = sessionRegistry;
        this.visualizationRegistry = visualizationRegistry;
    }

    /**
     * Processes all dirty sessions once per tick.
     */
    @Override
    public void run() {
        // Iterate all active sessions and render those marked dirty
        for (DebugSession<?> session : sessionRegistry.getAllSessions()) {
            // Allow rendering if dirty, even if COMPLETED/FAILED
            // This ensures the final frame is rendered before the session goes dormant
            boolean isTerminalButDirty = (session.getStatus() == SessionStatus.COMPLETED ||
                    session.getStatus() == SessionStatus.FAILED)
                    && session.isVisualizationDirty();
            if (!session.isActive() && !isTerminalButDirty) {
                continue;
            }
            if (!session.isVisualizationDirty()) {
                continue;
            }
            Object rawState = session.getState();
            if (rawState == null) {
                continue;
            }
            // Check and cast the state to DebugStateSnapshot informing the compiler of the object's type
            if (!(rawState instanceof DebugStateSnapshot state)) {
                // This case should ideally not be hit due to the check in DebugSession's constructor,
                // but this provides robust, defensive programming.
                continue;
            }
            // Look up provider for this state type and render
            visualizationRegistry.get(state.getClass()).ifPresent(provider -> {
                try {
                    // Log rendering event for verification (Mitigation M-02)
                    LOGGER.info(String.format("[IonCore Debug] Rendering frame for session %s via %s",
                            session.getOwner(), provider.getClass().getSimpleName()));
                    renderState(provider, state);
                    session.clearVisualizationDirty(); // Mark rendered
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error rendering debug visualization for session owned by " + session.getOwner(), e);
                }
            });
        }
    }

    /**
     * A private generic helper method to safely bridge the type gap.
     *
     * This method "captures" the specific type of the state and provider. By defining <TState>,
     * we tell the compiler that for the duration of this method call, the provider and the state
     * share the exact same generic type, making the call to provider.render(state) type-safe.
     *
     * @param provider
     *            The specific provider for the state type.
     * @param state
     *            The state object to be rendered.
     * @param <TState>
     *            The captured type that must implement DebugStateSnapshot.
     */
    @SuppressWarnings("unchecked")
    private <TState extends DebugStateSnapshot> void renderState(VisualizationProvider<?> provider, TState state) {
        // We can now safely cast the provider and call render with the logic in run() ensuring that the
        // provider is indeed the correct one for this state type.
        VisualizationProvider<TState> typedProvider = (VisualizationProvider<TState>) provider;
        typedProvider.render(state);
    }
}