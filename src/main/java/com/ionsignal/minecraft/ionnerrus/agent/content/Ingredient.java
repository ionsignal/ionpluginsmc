package com.ionsignal.minecraft.ionnerrus.agent.content;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An immutable record representing a recipe requirement, which can be either a single
 * specific material or a group of materials (e.g., from a tag).
 */
public record Ingredient(Set<Material> materials) {

    /**
     * Creates an Ingredient from a single material.
     *
     * @param material
     *            The material.
     * @return A new Ingredient instance.
     */
    public static Ingredient of(Material material) {
        return new Ingredient(Collections.singleton(material));
    }

    /**
     * Creates an Ingredient from a collection of materials.
     *
     * @param materials
     *            The collection of materials.
     * @return A new Ingredient instance.
     */
    public static Ingredient of(Collection<Material> materials) {
        // Use a HashSet to ensure the set is mutable for creation but the record field is not.
        return new Ingredient(Collections.unmodifiableSet(new HashSet<>(materials)));
    }

    /**
     * Checks if the given ItemStack satisfies this ingredient requirement.
     *
     * @param stack
     *            The ItemStack to test.
     * @return True if the stack's material is part of this ingredient's material set.
     */
    public boolean test(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        return materials.contains(stack.getType());
    }

    /**
     * Gets a single, deterministic material from this ingredient's set.
     * Useful for logging, requesting items, or as a fallback when a concrete
     * material is needed.
     *
     * @return The first material from the set.
     * @throws IllegalStateException
     *             if the ingredient is empty.
     */
    public Material getPreferredMaterial() {
        if (materials.isEmpty()) {
            throw new IllegalStateException("Cannot get a preferred material from an empty ingredient.");
        }
        // Sorting ensures deterministic output, which is good practice.
        return materials.stream().sorted().iterator().next();
    }

    @Override
    public String toString() {
        if (materials.size() == 1) {
            return getPreferredMaterial().name();
        }
        // For abstract ingredients, print the sorted list of materials.
        // Sorting ensures the output is deterministic for logging and debugging.
        return materials.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(", ", "[", "]"));
    }
}