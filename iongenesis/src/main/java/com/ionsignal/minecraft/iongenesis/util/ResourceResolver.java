package com.ionsignal.minecraft.iongenesis.util;

import com.dfsek.terra.api.registry.Registry;
import com.dfsek.terra.api.registry.key.RegistryKey;

import java.util.Optional;

/**
 * Utility class for resolving resources from Terra registries with standardized lookup logic.
 * Handles Exact Match (Namespaced) -> Fuzzy Match (ID only) -> Fallback Namespace.
 */
public final class ResourceResolver {

    private ResourceResolver() {
    }

    /**
     * Resolves a resource from a registry.
     *
     * @param registry
     *            The registry to look up in.
     * @param identifier
     *            The identifier string (e.g., "village:house" or "house").
     * @param fallbackNamespace
     *            The namespace to use if the identifier has none and fuzzy lookup fails.
     * @param <T>
     *            The type of the resource.
     * @return An Optional containing the resource if found.
     */
    public static <T> Optional<T> resolve(Registry<T> registry, String identifier, String fallbackNamespace) {
        if (identifier == null || identifier.isEmpty()) {
            return Optional.empty();
        }
        // Try exact match if the identifier is explicitly namespaced
        if (identifier.contains(":")) {
            try {
                return registry.get(RegistryKey.parse(identifier));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        // Try fuzzy lookup (Terra native behavior)
        // This finds the entry if the ID part matches exactly one entry in the registry
        Optional<T> fuzzy = registry.getByID(identifier);
        if (fuzzy.isPresent()) {
            return fuzzy;
        }
        // This handles fuzzy lookup fails when we want to assume the local pack
        try {
            return registry.get(RegistryKey.of(fallbackNamespace, identifier));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
