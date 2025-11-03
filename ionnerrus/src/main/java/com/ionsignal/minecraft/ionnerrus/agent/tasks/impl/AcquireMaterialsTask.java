package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.Ingredient;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService.CraftingPath;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService.CraftingStep;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalPrerequisite;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GetBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.RequestItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindCollectableBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * An analytical task that determines the next single prerequisite for a crafting plan. It scans the
 * environment, resolves the ambiguous, multi-path CraftingPlan into an optimal, concrete list of
 * required materials, and then identifies the first missing item to create a sub-goal for.
 */
public class AcquireMaterialsTask implements Task {
    private final RecipeService.CraftingBlueprint plan;
    private final Ingredient targetIngredient;
    private final int targetQuantity;
    private final BlockTagManager blockTagManager;
    private final Logger logger;
    private volatile boolean cancelled = false;

    // --- START MODIFICATION ---
    // The ResolutionResult record is no longer needed as these maps will be instance fields.
    // private record ResolutionResult(Map<Ingredient, Integer> shoppingList, Map<Ingredient,
    // CraftingPath> executionPlan) {}

    // Add new instance fields to hold the state of the recursive resolution process.
    private Map<Ingredient, Integer> shoppingList;
    private Map<Ingredient, CraftingPath> executionPlan;
    // --- END MODIFICATION ---

    public AcquireMaterialsTask(RecipeService.CraftingBlueprint plan, Ingredient targetIngredient, int targetQuantity,
            BlockTagManager blockTagManager) {
        this.plan = plan;
        this.targetIngredient = targetIngredient;
        this.targetQuantity = targetQuantity;
        this.blockTagManager = blockTagManager;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        // --- START REWRITE OF EXECUTE METHOD ---
        // Phase 1: Initialize the state-holding maps for this execution run.
        this.shoppingList = new HashMap<>();
        this.executionPlan = new HashMap<>();

        // Phase 2: Scan Environment for gatherable materials to inform path choices.
        return performEnvironmentalScan(agent)
                .thenCompose(nearbyMaterials -> {
                    if (cancelled) {
                        return CompletableFuture.failedFuture(new InterruptedException("Task cancelled during environmental scan."));
                    }

                    // Phase 3: Set up helpers for the recursive resolution.
                    Map<Ingredient, CraftingStep> stepLookup = plan.craftingSteps().stream()
                            .collect(Collectors.toMap(CraftingStep::ingredientToCraft, step -> step));
                    MemoizedCostCalculator calculator = new MemoizedCostCalculator(agent, stepLookup, nearbyMaterials);

                    // Phase 4: Kick off the top-down, inventory-aware, recursive resolution.
                    return resolveIngredient(targetIngredient, targetQuantity, stepLookup, agent, nearbyMaterials, calculator, 0)
                            .thenCompose(v -> {
                                if (cancelled) {
                                    return CompletableFuture
                                            .failedFuture(new InterruptedException("Task cancelled during plan resolution."));
                                }

                                // --- START MODIFICATION ---
                                // Added logging for the final results before determining the prerequisite.
                                logger.info("[AcquireMaterialsTask] Resolution complete.");
                                logger.info("[AcquireMaterialsTask] Final Shopping List: " + this.shoppingList);
                                String execPlanStr = this.executionPlan.entrySet().stream()
                                        .map(e -> e.getKey() + " -> " + e.getValue().recipes().get(0).getResult().getType())
                                        .collect(Collectors.joining(", "));
                                logger.info("[AcquireMaterialsTask] Final Execution Plan: {" + execPlanStr + "}");
                                // --- END MODIFICATION ---

                                // Phase 5: Compare the final, minimal shopping list to inventory to find the first missing item.
                                return determineFirstMissingMaterial(agent, this.shoppingList)
                                        .thenAccept(missingMaterialOpt -> {
                                            // Phase 6: Declare the result on the blackboard.
                                            if (missingMaterialOpt.isPresent()) {
                                                // A prerequisite was found.
                                                GoalPrerequisite prereq = createPrerequisiteFor(missingMaterialOpt.get());
                                                agent.getBlackboard().put(BlackboardKeys.NEXT_PREREQUISITE, prereq);
                                            } else {
                                                // No missing materials; all requirements are met.
                                                agent.getBlackboard().put(BlackboardKeys.CRAFTING_EXECUTION_PLAN, this.executionPlan);
                                                agent.getBlackboard().put(BlackboardKeys.MATERIALS_ACQUIRED, true);
                                            }
                                        });
                            });
                });
        // --- END REWRITE OF EXECUTE METHOD ---
    }

    // --- START REMOVAL OF OBSOLETE METHOD ---
    // This method is replaced by the new recursive `resolveIngredient` method.
    /*
     * private CompletableFuture<ResolutionResult> resolveConcreteShoppingList(NerrusAgent agent,
     * Set<Material> nearbyMaterials) {
     * Map<Ingredient, CraftingStep> stepLookup = plan.craftingSteps().stream()
     * .collect(Collectors.toMap(CraftingStep::ingredientToCraft, step -> step));
     * Map<Ingredient, Integer> shoppingList = new HashMap<>();
     * Map<Ingredient, CraftingPath> executionPlan = new HashMap<>();
     * MemoizedCostCalculator calculator = new MemoizedCostCalculator(agent, stepLookup,
     * nearbyMaterials);
     * return resolveIngredient(targetIngredient, targetQuantity, stepLookup, agent, nearbyMaterials,
     * calculator, shoppingList,
     * executionPlan)
     * .thenApply(v -> new ResolutionResult(shoppingList, executionPlan));
     * }
     */
    // --- END REMOVAL OF OBSOLETE METHOD ---

    // --- START NEW RECURSIVE RESOLVER METHOD ---
    /**
     * The core recursive function that resolves the dependencies for a single ingredient.
     * It checks inventory, calculates deficits, and recursively calls itself for sub-ingredients if
     * necessary.
     */
    private CompletableFuture<Void> resolveIngredient(Ingredient ingredient, int quantity, Map<Ingredient, CraftingStep> stepLookup,
            NerrusAgent agent, Set<Material> nearbyMaterials, MemoizedCostCalculator calculator, int depth) {
        if (cancelled) {
            return CompletableFuture.failedFuture(new InterruptedException("Task cancelled."));
        }

        // --- START MODIFICATION ---
        // Added logging throughout the resolver.
        final String indent = "  ".repeat(depth);
        logger.info(String.format("%s[Depth %d] Resolving %dx %s", indent, depth, quantity, ingredient));
        // --- END MODIFICATION ---

        // Create and memoize the future for this ingredient's resolution process.
        CompletableFuture<Void> resolutionFuture = new CountItemsSkill(ingredient.materials()).execute(agent)
                .thenCompose(inventory -> {
                    if (cancelled) {
                        return CompletableFuture.failedFuture(new InterruptedException("Task cancelled."));
                    }

                    int ownedAmount = ingredient.materials().stream().mapToInt(mat -> inventory.getOrDefault(mat, 0)).sum();
                    int deficit = quantity - ownedAmount;

                    // --- START MODIFICATION ---
                    logger.info(String.format("%s[Depth %d] -> Inventory has %d, deficit is %d", indent, depth, ownedAmount, deficit));
                    // --- END MODIFICATION ---

                    // // Base Case 1: We already have enough of this item. Prune this dependency branch.
                    // if (deficit <= 0) {
                    // // --- START MODIFICATION ---
                    // logger.info(String.format("%s[Depth %d] -> Requirement met. Pruning branch.", indent, depth));
                    // // --- END MODIFICATION ---
                    // return CompletableFuture.completedFuture(null);
                    // }

                    boolean isRawOrUncraftable = blockTagManager.isRawMaterial(ingredient.getPreferredMaterial())
                            || blockTagManager.isGatherable(ingredient.getPreferredMaterial())
                            || !stepLookup.containsKey(ingredient);

                    // Base Case 2: The item is a raw material or cannot be crafted. Add the deficit to the shopping
                    // list.
                    if (isRawOrUncraftable) {
                        // --- START MODIFICATION ---
                        logger.info(
                                String.format("%s[Depth %d] -> Is raw/uncraftable. Adding %d to shopping list.", indent, depth, deficit));
                        // --- END MODIFICATION ---
                        if (deficit > 0) {
                            shoppingList.merge(ingredient, deficit, Integer::sum);
                        }
                        // shoppingList.merge(ingredient, deficit, Integer::sum);
                        return CompletableFuture.completedFuture(null);
                    }

                    // Recursive Step: The item is craftable and we have a deficit.
                    CraftingStep step = stepLookup.get(ingredient);
                    return chooseBestPath(step, agent, stepLookup, nearbyMaterials, calculator)
                            .thenCompose(chosenPath -> {
                                // --- START MODIFICATION ---
                                String pathIngredients = chosenPath.requirements().entrySet().stream()
                                        .map(e -> e.getValue() + "x " + e.getKey())
                                        .collect(Collectors.joining(", "));
                                logger.info(String.format("%s[Depth %d] -> Chose path yielding %d with ingredients: {%s}", indent, depth,
                                        chosenPath.yield(), pathIngredients));
                                // --- END MODIFICATION ---

                                executionPlan.put(ingredient, chosenPath);

                                // LATEST FIX HERE: The original code pruned the entire branch if the deficit was <= 0,
                                // which resulted in an incomplete executionPlan. The new logic ensures that we always
                                // recurse into children to build the full plan. We only calculate a non-zero
                                // number of crafts (and thus a non-zero demand for child ingredients) if there is
                                // an actual deficit for the parent item.
                                int craftsToPerform = 0;
                                if (deficit > 0) {
                                    craftsToPerform = (int) Math.ceil((double) deficit / chosenPath.yield());
                                }

                                List<CompletableFuture<Void>> childFutures = new ArrayList<>();
                                for (Map.Entry<Ingredient, Integer> req : chosenPath.requirements().entrySet()) {
                                    // RECURSIVE CALL for each sub-ingredient.
                                    childFutures.add(resolveIngredient(req.getKey(), req.getValue() * craftsToPerform, stepLookup, agent,
                                            nearbyMaterials, calculator, depth + 1));
                                }
                                return CompletableFuture.allOf(childFutures.toArray(new CompletableFuture[0]));
                            });
                });

        return resolutionFuture;
    }

    private CompletableFuture<CraftingPath> chooseBestPath(CraftingStep step, NerrusAgent agent, Map<Ingredient, CraftingStep> stepLookup,
            Set<Material> nearbyMaterials, MemoizedCostCalculator calculator) {
        List<CompletableFuture<Map.Entry<CraftingPath, Integer>>> scoredPathFutures = new ArrayList<>();
        for (CraftingPath path : step.paths()) {
            CompletableFuture<Integer> totalPathCost = CompletableFuture.completedFuture(0);
            for (Ingredient requirement : path.requirements().keySet()) {
                totalPathCost = totalPathCost.thenCombine(calculator.calculateCost(requirement), Integer::sum);
            }
            scoredPathFutures.add(totalPathCost.thenApply(cost -> Map.entry(path, cost)));
        }
        return CompletableFuture.allOf(scoredPathFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> scoredPathFutures.stream()
                        .map(CompletableFuture::join)
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(step.paths().get(0))); // Fallback to first path
    }

    private CompletableFuture<Optional<Map.Entry<Ingredient, Integer>>> determineFirstMissingMaterial(NerrusAgent agent,
            Map<Ingredient, Integer> shoppingList) {
        Set<Material> allRequiredMaterials = shoppingList.keySet().stream()
                .flatMap(ing -> ing.materials().stream())
                .collect(Collectors.toSet());
        return new CountItemsSkill(allRequiredMaterials).execute(agent).thenApply(inventory -> {
            for (Map.Entry<Ingredient, Integer> required : shoppingList.entrySet()) {
                Ingredient ingredient = required.getKey();
                int requiredAmount = required.getValue();
                int ownedAmount = ingredient.materials().stream().mapToInt(mat -> inventory.getOrDefault(mat, 0)).sum();
                if (ownedAmount < requiredAmount) {
                    return Optional.of(Map.entry(ingredient, requiredAmount - ownedAmount));
                }
            }
            return Optional.empty();
        });
    }

    private GoalPrerequisite createPrerequisiteFor(Map.Entry<Ingredient, Integer> missing) {
        Ingredient ingredient = missing.getKey();
        int quantity = missing.getValue();
        Material preferredMaterial = ingredient.getPreferredMaterial();
        if (blockTagManager.isGatherable(preferredMaterial)) {
            Set<Material> group = blockTagManager.getMaterialSetFor(preferredMaterial);
            Optional<String> groupNameOpt = (group != null) ? blockTagManager.getGroupNameFor(group) : Optional.empty();
            if (groupNameOpt.isPresent()) {
                return new GoalPrerequisite("GET_BLOCKS", new GetBlockParameters(groupNameOpt.get(), quantity));
            }
        }
        if (plan.craftingSteps().stream().anyMatch(step -> step.ingredientToCraft().equals(ingredient))) {
            return new GoalPrerequisite("CRAFT_ITEM", new CraftItemParameters(preferredMaterial.name(), quantity));
        }
        return new GoalPrerequisite("REQUEST_ITEM", new RequestItemParameters(preferredMaterial.name(), quantity));
    }

    private CompletableFuture<Set<Material>> performEnvironmentalScan(NerrusAgent agent) {
        Set<Material> materialsToScanFor = getAllGatherableMaterialsFromPlan();
        if (materialsToScanFor.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        return new FindCollectableBlockSkill(materialsToScanFor, 64, new HashSet<>())
                .execute(agent)
                .thenApply(scanResult -> {
                    logger.info("AcquireMaterialsTask: Environmental scan found materials: " + scanResult.allFoundMaterials());
                    return scanResult.allFoundMaterials();
                });
    }

    private Set<Material> getAllGatherableMaterialsFromPlan() {
        Set<Material> gatherable = new HashSet<>();
        plan.rawIngredients().forEach((ingredient, count) -> {
            if (blockTagManager.isGatherable(ingredient.getPreferredMaterial())) {
                gatherable.addAll(ingredient.materials());
            }
        });
        plan.craftingSteps().forEach(step -> step.paths().forEach(path -> path.requirements().forEach((ingredient, count) -> {
            if (blockTagManager.isGatherable(ingredient.getPreferredMaterial())) {
                gatherable.addAll(ingredient.materials());
            }
        })));
        return gatherable;
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    private class MemoizedCostCalculator {
        private final Map<Ingredient, CompletableFuture<Integer>> memo = new HashMap<>();
        private final NerrusAgent agent;
        private final Map<Ingredient, CraftingStep> stepLookup;
        private final Set<Material> nearbyAvailableMaterials;

        MemoizedCostCalculator(NerrusAgent agent, Map<Ingredient, CraftingStep> stepLookup, Set<Material> nearbyMaterials) {
            this.agent = agent;
            this.stepLookup = stepLookup;
            this.nearbyAvailableMaterials = nearbyMaterials;
        }

        public CompletableFuture<Integer> calculateCost(Ingredient ingredient) {
            if (memo.containsKey(ingredient)) {
                return memo.get(ingredient);
            }
            CompletableFuture<Integer> costFuture = new CountItemsSkill(ingredient.materials()).execute(agent)
                    .thenCompose(inventory -> {
                        if (inventory.values().stream().mapToInt(Integer::intValue).sum() > 0)
                            return CompletableFuture.completedFuture(0);
                        if (blockTagManager.isGatherable(ingredient.getPreferredMaterial())) {
                            return CompletableFuture.completedFuture(
                                    !Collections.disjoint(ingredient.materials(), nearbyAvailableMaterials) ? 1 : 3);
                        }
                        CraftingStep step = stepLookup.get(ingredient);
                        if (step == null)
                            return CompletableFuture.completedFuture(4); // Must be requested

                        List<CompletableFuture<Integer>> pathCostFutures = new ArrayList<>();
                        for (CraftingPath path : step.paths()) {
                            CompletableFuture<Integer> totalPathCost = CompletableFuture.completedFuture(0);
                            for (Ingredient subIngredient : path.requirements().keySet()) {
                                totalPathCost = totalPathCost.thenCombine(calculateCost(subIngredient), Integer::sum);
                            }
                            pathCostFutures.add(totalPathCost);
                        }
                        return CompletableFuture.allOf(pathCostFutures.toArray(new CompletableFuture[0]))
                                .thenApply(v -> pathCostFutures.stream()
                                        .map(CompletableFuture::join)
                                        .filter(Objects::nonNull)
                                        .min(Integer::compareTo)
                                        .orElse(Integer.MAX_VALUE));
                    });
            memo.put(ingredient, costFuture);
            return costFuture;
        }
    }
}