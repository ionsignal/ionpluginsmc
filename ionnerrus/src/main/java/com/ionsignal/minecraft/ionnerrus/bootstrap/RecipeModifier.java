package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.PluginConfig;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Modifies vanilla Minecraft recipes for testing purposes.
 * This class is only active if the 'disableNonWoodRecipes' config flag is enabled.
 */
public class RecipeModifier {
    private static final Set<String> WOOD_KEYWORDS = Set.of("planks", "log", "wood", "stick", "crafting_table", "chest", "barrel");

    private final IonNerrus plugin;
    private final PluginConfig config;

    public RecipeModifier(IonNerrus plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Disables all non-wood crafting recipes if enabled in config.
     * This is a testing utility and should not be used in production.
     */
    public void disableNonWoodRecipesIfConfigured() {
        if (!config.isDisableNonWoodRecipes()) {
            return; // Feature disabled in config
        }
        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
        List<NamespacedKey> blacklist = new ArrayList<>();
        while (recipeIterator.hasNext()) {
            Recipe recipe = recipeIterator.next();
            if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) {
                NamespacedKey key = ((Keyed) recipe).getKey();
                if (key.getNamespace().equals(NamespacedKey.MINECRAFT)) {
                    boolean isWoodRecipe = WOOD_KEYWORDS.stream().anyMatch(keyword -> key.getKey().contains(keyword));
                    if (!isWoodRecipe) {
                        blacklist.add(key);
                    }
                }
            }
        }
        blacklist.forEach(Bukkit::removeRecipe);
        plugin.getLogger().info("Disabled " + blacklist.size() + " non-wood crafting recipes for testing.");
    }

    /**
     * Cleans up recipe modifications. Since recipe changes are permanent for the server session, this
     * is a no-op. Recipes will reset on next server restart.
     */
    public void cleanup() {
        // No action needed - recipe modifications persist until server restart
        plugin.getLogger().info("Recipe modifier cleanup complete (no action required).");
    }
}