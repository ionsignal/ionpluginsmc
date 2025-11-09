package com.ionsignal.minecraft.ioncore.debug;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for storing and retrieving visualization providers.
 * Each debug state type can have an associated visualization provider that handles
 * rendering its specific state.
 * 
 * Accessed globally via {@link IonCore} singleton to allow cross-plugin registration.
 * 
 * Thread Safety: All operations use {@link ConcurrentHashMap} for thread-safe concurrent access.
 */
public class VisualizationProviderRegistry {
    private final Map<Class<?>, VisualizationProvider<?>> providers = new ConcurrentHashMap<>();

    /**
     * Registers a visualization provider for a specific state type.
     * 
     * @param <TState>
     *            The immutable state type this provider handles
     * @param stateClass
     *            The {@link Class} object representing the state type
     * @param provider
     *            The provider instance to register
     * @throws IllegalArgumentException
     *             if {@code stateClass} or {@code provider} is {@code null}
     */
    public <TState extends DebugStateSnapshot> void register(
            Class<TState> stateClass,
            VisualizationProvider<TState> provider) {
        if (stateClass == null || provider == null) {
            throw new IllegalArgumentException("State class and provider cannot be null");
        }
        providers.put(stateClass, provider);
    }

    /**
     * Gets a registered provider for a given state type.
     * 
     * @param <TState>
     *            The state type to look up
     * @param stateClass
     *            The {@link Class} object representing the state type
     * @return {@link Optional} containing the provider, or empty if not registered
     */
    @SuppressWarnings("unchecked")
    public <TState extends DebugStateSnapshot> Optional<VisualizationProvider<TState>> get(
            Class<TState> stateClass) {
        VisualizationProvider<?> provider = providers.get(stateClass);
        return Optional.ofNullable((VisualizationProvider<TState>) provider);
    }

    /**
     * Unregisters a provider for a given state type.
     * 
     * @param stateClass
     *            The {@link Class} object representing the state type
     * @return {@code true} if a provider was removed, {@code false} if none was registered
     */
    public boolean unregister(Class<?> stateClass) {
        return providers.remove(stateClass) != null;
    }

    /**
     * Clears all registered providers.
     */
    public void clear() {
        providers.clear();
    }
}