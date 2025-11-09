package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

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
    private final IonNerrus plugin;
    private static final Set<String> WOOD_KEYWORDS = Set.of("planks", "log", "wood", "stick", "crafting_table", "chest", "barrel");

    public RecipeModifier(IonNerrus plugin) {
        this.plugin = plugin;
    }

    /**
     * Disables all non-wood crafting recipes if enabled in config.
     * This is a testing utility and should not be used in production.
     */
    public void disableNonWoodRecipesIfConfigured() {
        if (!plugin.getPluginConfig().isDisableNonWoodRecipes()) {
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
}