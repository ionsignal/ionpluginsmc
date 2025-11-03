package com.ionsignal.minecraft.ionnerrus.agent.goals.impl.helpers;

import com.ionsignal.minecraft.ionnerrus.agent.content.Ingredient;
import org.bukkit.Material;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Manages the state of a complex crafting job, acting as a virtual inventory.
 * It tracks available materials, checks recipe feasibility, and consumes/adds items
 * as the crafting process unfolds.
 */
public class CraftingContext {
    private final Map<Material, Integer> availableMaterials;

    public CraftingContext(Map<Material, Integer> initialMaterials) {
        this.availableMaterials = new HashMap<>(initialMaterials);
    }

    /**
     * Checks if the context has enough materials to satisfy a specific recipe once.
     * This is a non-destructive check.
     */
    public boolean hasIngredientsFor(CraftingRecipe recipe) {
        Map<Material, Integer> tempInventory = new HashMap<>(this.availableMaterials);
        List<RecipeChoice> choices = getRecipeChoices(recipe);

        for (RecipeChoice choice : choices) {
            if (choice == null)
                continue;
            boolean foundMatch = false;
            for (Material material : getMaterialsFromChoice(choice)) {
                if (tempInventory.getOrDefault(material, 0) > 0) {
                    tempInventory.merge(material, -1, Integer::sum);
                    foundMatch = true;
                    break;
                }
            }
            if (!foundMatch) {
                return false; // Could not satisfy this ingredient choice.
            }
        }
        return true; // All ingredients satisfied.
    }

    /**
     * Consumes the ingredients for a given recipe from the available materials.
     * This is a destructive operation.
     */
    public void consumeIngredientsFor(CraftingRecipe recipe) {
        List<RecipeChoice> choices = getRecipeChoices(recipe);
        for (RecipeChoice choice : choices) {
            if (choice == null)
                continue;
            // Find which specific material was used and decrement it.
            for (Material material : getMaterialsFromChoice(choice)) {
                if (availableMaterials.getOrDefault(material, 0) > 0) {
                    availableMaterials.merge(material, -1, Integer::sum);
                    break; // Consume one and move to the next choice.
                }
            }
        }
    }

    /**
     * Adds a crafted item to the context's available materials.
     */
    public void addCraftedItem(ItemStack result) {
        availableMaterials.merge(result.getType(), result.getAmount(), Integer::sum);
    }

    /**
     * Gets the total count of all items in the context that match an abstract ingredient.
     */
    public int getAvailableCount(Ingredient ingredient) {
        return ingredient.materials().stream()
                .mapToInt(mat -> availableMaterials.getOrDefault(mat, 0))
                .sum();
    }

    private List<RecipeChoice> getRecipeChoices(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            // We now filter out these nulls to get a clean list of actual ingredients.
            return shaped.getChoiceMap().values().stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            return shapeless.getChoiceList();
        }
        return List.of();
    }

    private List<Material> getMaterialsFromChoice(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            return materialChoice.getChoices();
        } else if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
            return exactChoice.getChoices().stream().map(ItemStack::getType).toList();
        }
        return List.of();
    }
}