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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * This service resolves recipe dependencies and returns ALL possible crafting paths without
 * considering world state, inventory, or agent location. The resulting CraftingBlueprint is
 * context-free and can be reused by multiple agents.
 */
public class RecipeService {
    private static final int MAX_RESOLUTION_DEPTH = 20;

    private final BlockTagManager blockTagManager;
    private final ComponentLogger logger;

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

    public RecipeService(BlockTagManager blockTagManager) {
        this.logger = IonNerrus.getInstance().getModernLogger();
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
    public Optional<CraftingBlueprint> createCraftingBlueprint(Material targetMaterial, int quantity) {
        if (targetMaterial == null) {
            throw new IllegalArgumentException("targetMaterial cannot be null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got: " + quantity);
        }
        if (!targetMaterial.isItem()) {
            logger.warn("Cannot craft non-item material: " + targetMaterial);
            return Optional.empty();
        }
        try {
            Map<Ingredient, Integer> requiredItems = new HashMap<>();
            requiredItems.put(Ingredient.of(targetMaterial), quantity);
            Map<Ingredient, Integer> totalRawMaterials = new HashMap<>();
            List<CraftingStep> orderedSteps = new ArrayList<>();
            logger.debug("[RecipeService] Starting crafting plan for " + quantity + "x " + targetMaterial);
            // Begin resolution with cycle detection
            Set<Ingredient> resolutionStack = new HashSet<>();
            resolveDependencies(requiredItems, totalRawMaterials, orderedSteps, resolutionStack, 0);
            return Optional.of(new CraftingBlueprint(totalRawMaterials, orderedSteps));
        } catch (StackOverflowError e) {
            // Explicit handling for stack overflow (indicates circular dependency)
            logger.error("Recipe resolution caused stack overflow (circular dependency?) for " + targetMaterial);
            return Optional.empty();
        } catch (IllegalStateException e) {
            // Catch depth limit exceeded
            logger.error("Failed to create crafting plan for " + targetMaterial + ": " + e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            // Catch all other exceptions to prevent plugin crashes
            logger.error("Unexpected error creating crafting plan for " + targetMaterial);
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
     * @param resolutionStack
     *            A set to track the current resolution chain for cycle detection ONLY.
     */
    @SuppressWarnings("null")
    private void resolveDependencies(
            Map<Ingredient, Integer> itemsToCraft,
            Map<Ingredient, Integer> totalRawMaterials,
            List<CraftingStep> orderedSteps,
            Set<Ingredient> resolutionStack,
            int depth) {
        // Depth limit check
        if (depth > MAX_RESOLUTION_DEPTH) {
            throw new IllegalStateException(
                    String.format("Recipe resolution exceeded maximum depth of %d. Possible circular dependency.", MAX_RESOLUTION_DEPTH));
        }
        // Build memoization set once for O(1) lookups (was O(n) per ingredient)
        Set<Ingredient> resolvedIngredients = orderedSteps.stream()
                .map(CraftingStep::ingredientToCraft)
                .collect(Collectors.toSet());
        String indent = "  ".repeat(depth);
        String itemsStr = itemsToCraft.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey())
                .collect(Collectors.joining(", "));
        logger.debug(String.format("%s[Depth %d] Resolving dependencies for: {%s}", indent, depth, itemsStr));
        for (Map.Entry<Ingredient, Integer> entry : itemsToCraft.entrySet()) {
            Ingredient ingredient = entry.getKey();
            int needed = entry.getValue();
            // Memoization check - O(1) check for already-resolved ingredients
            if (resolvedIngredients.contains(ingredient)) {
                logger.debug(String.format("%s -> Already resolved %s. Skipping.", indent, ingredient));
                continue;
            }
            // Cycle detection - Check if ingredient is in current resolution chain
            if (resolutionStack.contains(ingredient)) {
                throw new IllegalStateException("Circular recipe dependency detected: " + ingredient);
            }
            // Add to resolution stack for cycle detection
            resolutionStack.add(ingredient);
            // Wrap entire processing in try-finally for guaranteed cleanup
            try {
                Material representativeMaterial = ingredient.getPreferredMaterial();
                // Terminal case: Raw or gatherable material
                if (blockTagManager.isRawMaterial(representativeMaterial) || blockTagManager.isGatherable(representativeMaterial)) {
                    logger.debug(String.format("%s -> Terminal node %s (is a raw/gatherable material). Adding to raw materials list.",
                            indent, ingredient));
                    totalRawMaterials.merge(ingredient, needed, Integer::sum);
                    continue; // Early return - ingredient removed in finally block
                }
                // Find recipes for this ingredient
                List<CraftingRecipe> recipes = findRecipesForIngredient(ingredient);
                if (recipes.isEmpty()) {
                    logger.debug(String.format("%s -> Terminal node %s (no recipe). Adding to raw materials list.", indent, ingredient));
                    totalRawMaterials.merge(ingredient, needed, Integer::sum);
                    continue; // Early return - ingredient removed in finally block
                }
                // Group recipes by their abstract ingredient signature
                Map<Map<Ingredient, Integer>, List<CraftingRecipe>> recipeGroups = recipes.stream()
                        .collect(Collectors.groupingBy(this::getIngredientSignature));
                // Create CraftingPath objects for each group
                List<CraftingPath> paths = recipeGroups.entrySet().stream()
                        .map(groupEntry -> {
                            int yield = groupEntry.getValue().get(0).getResult().getAmount();
                            return new CraftingPath(groupEntry.getKey(), groupEntry.getValue(), yield);
                        })
                        .toList();
                // Calculate crafts needed (using first path's yield as representative)
                int representativeYield = paths.get(0).yield();
                int craftsToPerform = (int) Math.ceil((double) needed / representativeYield);
                logger.debug(String.format(
                        "%s -> Found %d recipe(s) for %s, grouped into %d path(s). Representative yield: %d. Need %d craft(s).",
                        indent, recipes.size(), ingredient, paths.size(), representativeYield, craftsToPerform));
                // Collect all sub-ingredients from all paths
                Map<Ingredient, Integer> allSubIngredients = new HashMap<>();
                for (CraftingPath path : paths) {
                    path.requirements().forEach((subIngredient, quantity) -> {
                        allSubIngredients.merge(subIngredient, quantity * craftsToPerform, Integer::sum);
                    });
                }
                // Recurse into sub-ingredients if any exist
                if (!allSubIngredients.isEmpty()) {
                    String subIngStr = allSubIngredients.entrySet().stream()
                            .map(e -> e.getValue() + "x " + e.getKey())
                            .collect(Collectors.joining(", "));
                    logger.debug(String.format("%s -> All possible sub-ingredients needed: {%s}", indent, subIngStr));
                    logger.debug(String.format("%s -> Descending into dependencies for %s...", indent, ingredient));
                    // Pass resolutionStack down the chain
                    resolveDependencies(allSubIngredients, totalRawMaterials, orderedSteps, resolutionStack, depth + 1);
                }
                // Add this ingredient to the ordered steps
                orderedSteps.add(new CraftingStep(ingredient, paths));
            } finally {
                // Remove from resolution stack when done (backtracking)
                // This allows the same ingredient to appear in different branches
                resolutionStack.remove(ingredient);
            }
        }
        logger.debug(String.format("%s[Depth %d] Finished resolving for: {%s}", indent, depth, itemsStr));
    }

    /**
     * Finds all suitable CraftingRecipes for a given abstract ingredient.
     * Thread-safe wrapper that delegates to main thread if needed.
     */
    private List<CraftingRecipe> findRecipesForIngredient(Ingredient ingredient) {
        // Check if we're already on the main thread to avoid unnecessary executor overhead
        if (Bukkit.isPrimaryThread()) {
            return findRecipesForIngredientSync(ingredient);
        }
        // Otherwise, delegate to main thread and block (acceptable for planning phase)
        CompletableFuture<List<CraftingRecipe>> future = CompletableFuture.supplyAsync(
                () -> findRecipesForIngredientSync(ingredient),
                IonNerrus.getInstance().getMainThreadExecutor());
        try {
            return future.get(); // Block until main thread completes the lookup
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to fetch recipes for ingredient: " + ingredient, e);
            return Collections.emptyList();
        }
    }

    /**
     * Synchronous method must be called on the main server thread due to Bukkit API usage.
     */
    private List<CraftingRecipe> findRecipesForIngredientSync(Ingredient ingredient) {
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
    @SuppressWarnings("null")
    private Map<Ingredient, Integer> getIngredientSignature(CraftingRecipe recipe) {
        Map<Ingredient, Integer> signature = new HashMap<>();
        for (RecipeChoice choice : getRecipeChoices(recipe)) {
            if (choice == null)
                continue;
            Ingredient specificIngredient = resolveRecipeChoice(choice);
            if (specificIngredient != null) {
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