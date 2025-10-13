package com.ionsignal.minecraft.ionnerrus.agent.content;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A service for resolving Minecraft crafting recipes into a complete, actionable plan. It
 * recursively calculates all raw material requirements and determines the correct, ordered sequence
 * of intermediate crafting steps needed to produce a final item.
 */
public class RecipeService {
    private final BlockTagManager blockTagManager;
    private final Logger logger;

    public RecipeService(BlockTagManager blockTagManager) {
        this.logger = IonNerrus.getInstance().getLogger();
        this.blockTagManager = blockTagManager;
    }

    /**
     * Creates a complete, ordered plan for crafting an item.
     *
     * @param targetMaterial
     *            The final item to be crafted.
     * @param quantity
     *            The desired quantity of the final item.
     * @return An Optional containing the full CraftingPlan, or empty if the item cannot be crafted.
     */
    public Optional<CraftingBlueprint> createCraftingPlan(Material targetMaterial, int quantity) {
        try {
            Map<Ingredient, Integer> requiredItems = new HashMap<>();
            requiredItems.put(Ingredient.of(targetMaterial), quantity);
            Map<Ingredient, Integer> totalRawMaterials = new HashMap<>();
            List<CraftingStep> orderedSteps = new ArrayList<>();
            logger.info("[RecipeService] Starting crafting plan for " + quantity + "x " + targetMaterial);
            resolveDependencies(requiredItems, totalRawMaterials, orderedSteps, 0);
            return Optional.of(new CraftingBlueprint(totalRawMaterials, orderedSteps));
        } catch (IllegalStateException e) {
            logger.severe("Failed to create crafting plan for " + targetMaterial + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The core recursive function that performs a dependency-graph traversal (depth-first search)
     * to calculate raw materials and determine the correct crafting order (topological sort).
     *
     * @param itemsToCraft
     *            A map of materials and quantities needed at the current level of recursion.
     * @param totalRawMaterials
     *            A map that accumulates the final raw material shopping list.
     * @param orderedSteps
     *            A list that accumulates the correctly ordered crafting steps (post-order traversal).
     * @param visited
     *            A set to track visited nodes in the dependency graph to prevent redundant
     *            processing and infinite loops.
     */
    private void resolveDependencies(
            Map<Ingredient, Integer> itemsToCraft,
            Map<Ingredient, Integer> totalRawMaterials,
            List<CraftingStep> orderedSteps,
            int depth) {
        String indent = "  ".repeat(depth);
        String itemsStr = itemsToCraft.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey())
                .collect(Collectors.joining(", "));
        logger.info(String.format("%s[Depth %d] Resolving dependencies for: {%s}", indent, depth, itemsStr));
        for (Map.Entry<Ingredient, Integer> entry : new HashMap<>(itemsToCraft).entrySet()) {
            Ingredient ingredient = entry.getKey();
            int needed = entry.getValue();
            // The check for already-completed steps is now done by inspecting the final orderedSteps list,
            // which is more reliable than the old visited set.
            if (orderedSteps.stream().anyMatch(step -> step.ingredientToCraft().equals(ingredient))) {
                logger.info(String.format("%s -> Already resolved %s. Skipping.", indent, ingredient));
                continue;
            }
            Material representativeMaterial = ingredient.getPreferredMaterial();
            if (blockTagManager.isRawMaterial(representativeMaterial) || blockTagManager.isGatherable(representativeMaterial)) {
                logger.info(String.format("%s -> Terminal node %s (is a raw/gatherable material). Adding %d to raw materials.", indent,
                        ingredient,
                        needed));
                totalRawMaterials.merge(ingredient, needed, Integer::sum);
                continue;
            }
            List<CraftingRecipe> recipes = findRecipesForIngredient(ingredient);
            if (recipes.isEmpty()) {
                logger.info(String.format("%s -> Terminal node %s (no recipe). Adding %d to raw materials.",
                        indent, ingredient, needed));
                totalRawMaterials.merge(ingredient, needed, Integer::sum);
                continue;
            }
            // Group recipes by their abstract ingredient signature.
            // Map<Map<Ingredient, Integer>, List<CraftingRecipe>> recipeGroups = recipes.stream()
            // .collect(Collectors.groupingBy(this::getAbstractIngredientSignature));
            Map<Map<Ingredient, Integer>, List<CraftingRecipe>> recipeGroups = recipes.stream()
                    .collect(Collectors.groupingBy(this::getIngredientSignature));
            // Create CraftingPath objects for each group.
            List<CraftingPath> paths = recipeGroups.entrySet().stream()
                    .map(groupEntry -> {
                        // Get yield from the group, assuming all recipes in a group have the same yield.
                        int yield = groupEntry.getValue().get(0).getResult().getAmount();
                        return new CraftingPath(groupEntry.getKey(), groupEntry.getValue(), yield);
                    })
                    .toList();
            // We still need a representative yield to calculate craftsToPerform for the recursion.
            // We'll just use the first path's yield for this. The execution logic will use the correct yield
            // later.
            int representativeYield = paths.get(0).yield();
            int craftsToPerform = (int) Math.ceil((double) needed / representativeYield);
            logger.info(String.format(
                    "%s -> Found %d recipe(s) for %s, grouped into %d distinct path(s). Representative yield: %d. Need to perform %d craft(s).",
                    indent, recipes.size(), ingredient, paths.size(), representativeYield, craftsToPerform));
            // Collect all possible sub-ingredients from all paths.
            Map<Ingredient, Integer> allSubIngredients = new HashMap<>();
            for (CraftingPath path : paths) {
                path.requirements().forEach((subIngredient, quantity) -> {
                    allSubIngredients.merge(subIngredient, quantity * craftsToPerform, Integer::sum);
                });
            }
            if (!allSubIngredients.isEmpty()) {
                String subIngStr = allSubIngredients.entrySet().stream().map(e -> e.getValue() + "x " + e.getKey())
                        .collect(Collectors.joining(", "));
                logger.info(String.format("%s -> All possible sub-ingredients needed: {%s}", indent, subIngStr));
                logger.info(String.format("%s -> Descending into dependencies for %s...", indent, ingredient));
                resolveDependencies(allSubIngredients, totalRawMaterials, orderedSteps, depth + 1);
            }
            logger.info(String.format("%s -> Finished dependencies for %s. Adding to ordered steps.", indent, ingredient));
            orderedSteps.add(new CraftingStep(ingredient, paths));
        }
        logger.info(String.format("%s[Depth %d] Finished resolving for: {%s}", indent, depth, itemsStr));
    }

    /**
     * Represents a single, distinct crafting action to be performed.
     *
     * @param ingredientToCraft
     *            The abstract ingredient to be crafted in this step.
     * @param paths
     *            A list of all possible ways (paths) to craft the item, grouped by
     *            their abstract ingredient requirements.
     */
    public record CraftingStep(Ingredient ingredientToCraft, List<CraftingPath> paths) {
    }

    /**
     * Represents the complete plan for a crafting directive.
     *
     * @param rawIngredients
     *            The final "shopping list" of non-craftable materials required.
     * @param craftingSteps
     *            An ordered list of intermediate and final crafting actions. Dependencies
     *            will always appear before the items that require them.
     */
    public record CraftingBlueprint(Map<Ingredient, Integer> rawIngredients, List<CraftingStep> craftingSteps) {
    }

    /**
     * Represents a distinct method for crafting an item, defined by a specific set of
     * abstract ingredients.
     *
     * @param requirements
     *            The map of abstract Ingredients and quantities required for this path.
     * @param recipes
     *            The list of concrete Bukkit recipes that follow this ingredient path.
     */
    public record CraftingPath(Map<Ingredient, Integer> requirements, List<CraftingRecipe> recipes, int yield) {
    }

    /**
     * Finds all suitable CraftingRecipes for a given abstract ingredient.
     */
    private List<CraftingRecipe> findRecipesForIngredient(Ingredient ingredient) {
        return ingredient.materials().stream()
                .flatMap(material -> Bukkit.getServer().getRecipesFor(new ItemStack(material)).stream())
                .filter(CraftingRecipe.class::isInstance)
                .map(CraftingRecipe.class::cast)
                .distinct()
                .toList();
    }

    /**
     * Extracts the list of RecipeChoices from a given CraftingRecipe.
     */
    private List<RecipeChoice> getRecipeChoices(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            // We only care about the ingredients, not their shape, for dependency resolution.
            return new ArrayList<>(shapedRecipe.getChoiceMap().values());
        } else if (recipe instanceof ShapelessRecipe shapelessRecipe) {
            return shapelessRecipe.getChoiceList();
        }
        return Collections.emptyList();
    }

    /**
     * Resolves a RecipeChoice to an abstract Ingredient, capturing all possible materials.
     */
    private Ingredient resolveRecipeChoice(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            Collection<Material> materials = materialChoice.getChoices();
            if (!materials.isEmpty()) {
                return Ingredient.of(materials);
            }
        } else if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
            Collection<Material> materials = exactChoice.getChoices().stream()
                    .map(ItemStack::getType)
                    .collect(Collectors.toList());
            if (!materials.isEmpty()) {
                return Ingredient.of(materials);
            }
        }
        return null;
    }

    /**
     * Creates an "ingredient signature" for a recipe, which is a map of its ingredients and
     * their counts. This is used to group recipes that are functionally identical.
     *
     * @param recipe
     *            The recipe to analyze.
     * @return A map representing the ingredient signature.
     */
    private Map<Ingredient, Integer> getIngredientSignature(CraftingRecipe recipe) {
        Map<Ingredient, Integer> signature = new HashMap<>();
        for (RecipeChoice choice : getRecipeChoices(recipe)) {
            if (choice == null)
                continue;
            Ingredient specificIngredient = resolveRecipeChoice(choice);
            if (specificIngredient != null) {
                // This was the source of the bug. By removing the generalization here,
                // a recipe requiring a specific log (ACACIA_LOG) will now correctly
                // create a signature with that specific log, not the entire "wood" group.
                // The generalization to tags (like any plank for a chest) still works
                // because the RecipeChoice itself contains all the materials from the tag.
                signature.merge(specificIngredient, 1, Integer::sum);
            }
        }
        return signature;
    }

    /**
     * Checks if a recipe must be crafted in a 3x3 grid (i.e., requires a crafting table).
     */
    public static boolean is3x3Recipe(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            if (shapedRecipe.getShape().length > 2)
                return true;
            for (String row : shapedRecipe.getShape()) {
                if (row.length() > 2)
                    return true;
            }
        } else if (recipe instanceof ShapelessRecipe shapelessRecipe) {
            return shapelessRecipe.getChoiceList().size() > 4;
        }
        return false;
    }
}