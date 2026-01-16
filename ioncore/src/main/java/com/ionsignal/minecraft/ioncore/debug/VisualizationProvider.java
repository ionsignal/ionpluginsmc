package com.ionsignal.minecraft.ioncore.debug;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable interface for rendering debug visualizations.
 * Implementations handle all visualization rendering for a specific debug state type.
 * 
 * Thread Safety: All methods must be thread-safe. Blocking operations should run on
 * the appropriate executor (main thread for Bukkit API calls, offload for heavy work).
 * 
 * @param <TState>
 *            The type of debug state this provider can visualize. Must implement
 *            {@link DebugStateSnapshot}.
 */
public interface VisualizationProvider<TState extends DebugStateSnapshot> {
    /**
     * Renders visualization for the given state.
     * MUST be called on the main server thread if using Bukkit/NMS APIs.
     * 
     * This method is called when {@code session.isVisualizationDirty()} is {@code true}.
     * 
     * @param state
     *            The current debug state to visualize (immutable snapshot)
     * @throws IllegalStateException
     *             if called from wrong thread context
     */
    void render(TState state);

    /**
     * Clean up all visualizations for this provider.
     * Called when the plugin is shutting down.
     * 
     * @return A {@link CompletableFuture} that completes when cleanup is done
     */
    default CompletableFuture<Void> cleanup() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Clean up visualizations for a specific session.
     * Called when a debug session is ended or cancelled.
     * 
     * @param sessionId
     *            The UUID of the session to clean up.
     * @return A {@link CompletableFuture} that completes when cleanup is done
     */
    default CompletableFuture<Void> cleanup(UUID sessionId) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Gets the state type this provider can visualize.
     * Used for type-safe provider lookup and filtering.
     * 
     * @return The {@link Class} object for {@code TState}
     */
    Class<TState> getStateType();
}