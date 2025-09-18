package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.Ingredient;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService.CraftingPath;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService.CraftingStep;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.helpers.CraftingContext;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindCollectableBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.RequestItemSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.GatherBlockTask.GatherResult;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A task to acquire a list of required raw materials. It first performs an environmental scan, then
 * resolves the ambiguous, multi-path CraftingPlan into a single, optimal, and concrete list of
 * required materials based on the agent's current inventory and local environment. Finally, it
 * proceeds to gather or request only the materials it is missing.
 */
public class AcquireMaterialsTask implements Task {
    private final Ingredient targetIngredient;
    private final int targetQuantity;
    private final RecipeService.CraftingPlan plan;
    private final BlockTagManager blockTagManager;
    private final Logger logger;
    private volatile boolean cancelled = false;
    private Map<Ingredient, Integer> concreteShoppingList;

    public AcquireMaterialsTask(RecipeService.CraftingPlan plan, Ingredient targetIngredient, int targetQuantity,
            BlockTagManager blockTagManager) {
        this.plan = plan;
        this.targetIngredient = targetIngredient;
        this.targetQuantity = targetQuantity;
        this.blockTagManager = blockTagManager;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        // Phase 1: Scan Environment.
        return performEnvironmentalScan(agent)
                .thenCompose(nearbyAvailableMaterials -> {
                    if (cancelled)
                        return CompletableFuture.failedFuture(new InterruptedException("Task cancelled during environmental scan."));
                    // Phase 2: Set up and initiate the recursive plan resolution.
                    logger.info("AcquireMaterialsTask: Starting recursive resolution for " + targetQuantity + "x " + targetIngredient);
                    // Create a lookup map for efficient access to crafting steps.
                    Map<Ingredient, CraftingStep> stepLookup = plan.craftingSteps().stream()
                            .collect(Collectors.toMap(CraftingStep::ingredientToCraft, step -> step));
                    // Initialize the member field for the shopping list and the resolver populates the member field.
                    this.concreteShoppingList = new HashMap<>();
                    MemoizedCostCalculator calculator = new MemoizedCostCalculator(agent, stepLookup, nearbyAvailableMaterials);
                    return resolveIngredient(targetIngredient, targetQuantity, stepLookup, agent,
                            nearbyAvailableMaterials, calculator)
                                    .thenCompose(v -> {
                                        if (cancelled)
                                            return CompletableFuture
                                                    .failedFuture(new InterruptedException("Task cancelled during plan resolution."));
                                        logger.info("AcquireMaterialsTask: Final concrete shopping list: " + concreteShoppingList);
                                        // Phase 3 & 4 are now handled by the new acquisitionLoop.
                                        return acquisitionLoop(agent, nearbyAvailableMaterials);
                                    });
                })
                .thenCompose(v -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    logger.info("AcquireMaterialsTask: Acquisition complete. Finalizing context.");
                    // Get a definitive count of all materials on our shopping list.
                    Set<Material> allRelevantMaterials = concreteShoppingList.keySet().stream()
                            .flatMap(ing -> ing.materials().stream())
                            .collect(Collectors.toSet());
                    // Also include materials for the final product, in case we already have some.
                    allRelevantMaterials.addAll(targetIngredient.materials());
                    return new CountItemsSkill(allRelevantMaterials).execute(agent);
                })
                .thenAccept(finalInventoryCount -> {
                    if (cancelled)
                        return;
                    // Create the context and place it on the blackboard for the Goal.
                    CraftingContext context = new CraftingContext(finalInventoryCount);
                    agent.getBlackboard().put(BlackboardKeys.CRAFTING_CONTEXT, context);
                    logger.info("AcquireMaterialsTask: CraftingContext created and placed on blackboard.");
                });
    }

    /**
     * Scans the local environment for all gatherable materials relevant to the current crafting plan.
     */
    private CompletableFuture<Set<Material>> performEnvironmentalScan(NerrusAgent agent) {
        Set<Material> materialsToScanFor = getAllGatherableMaterialsFromPlan();
        if (materialsToScanFor.isEmpty()) {
            logger.info("AcquireMaterialsTask: No gatherable materials in the plan to scan for.");
            return CompletableFuture.completedFuture(Set.of());
        }
        logger.info("AcquireMaterialsTask: Scanning environment for: " + materialsToScanFor);
        // We only care about the list of found materials, not finding a specific target block.
        // The skill is now repurposed as a general-purpose scanner.
        return new FindCollectableBlockSkill(materialsToScanFor, 64, new HashSet<>())
                .execute(agent)
                .thenApply(scanResult -> {
                    logger.info("AcquireMaterialsTask: Environmental scan found materials: " + scanResult.allFoundMaterials());
                    return scanResult.allFoundMaterials();
                });
    }

    /**
     * Extracts every possible gatherable material from the entire CraftingPlan.
     */
    private Set<Material> getAllGatherableMaterialsFromPlan() {
        Set<Material> gatherable = new HashSet<>();
        // Check raw ingredients
        plan.rawIngredients().forEach((ingredient, count) -> {
            if (blockTagManager.isGatherable(ingredient.getPreferredMaterial())) {
                gatherable.addAll(ingredient.materials());
            }
        });
        // Check all ingredients in all crafting paths
        plan.craftingSteps().forEach(step -> {
            step.paths().forEach(path -> {
                path.requirements().forEach((ingredient, count) -> {
                    if (blockTagManager.isGatherable(ingredient.getPreferredMaterial())) {
                        gatherable.addAll(ingredient.materials());
                    }
                });
            });
        });
        return gatherable;
    }

    /**
     * Recursively resolves the dependencies for a single ingredient, making mutually exclusive choices
     * at each step to build a concrete, minimal shopping list.
     *
     * @param ingredientToResolve
     *            The ingredient to resolve now.
     * @param quantityNeeded
     *            The amount of the ingredient needed by its parent.
     * @param stepLookup
     *            A map of all possible crafting steps for fast lookups.
     * @param agent
     *            The agent, for inventory context.
     * @param nearbyAvailableMaterials
     *            A set of materials found in the local environment.
     * @return A CompletableFuture that completes when this branch of the dependency graph is resolved.
     */
    private CompletableFuture<Void> resolveIngredient(
            Ingredient ingredientToResolve,
            int quantityNeeded,
            Map<Ingredient, CraftingStep> stepLookup,
            NerrusAgent agent,
            Set<Material> nearbyAvailableMaterials,
            MemoizedCostCalculator calculator) {
        if (cancelled) {
            return CompletableFuture.failedFuture(new InterruptedException("Task cancelled during ingredient resolution."));
        }
        // Base Case 1: The ingredient is a raw material that is gathered, not crafted.
        if (blockTagManager.isRawMaterial(ingredientToResolve.getPreferredMaterial())
                || blockTagManager.isGatherable(ingredientToResolve.getPreferredMaterial())) {
            logger.info("[Resolver] Base Case (Raw/Gatherable): Adding " + quantityNeeded + "x " + ingredientToResolve
                    + " to shopping list.");
            concreteShoppingList.merge(ingredientToResolve, quantityNeeded, Integer::sum);
            return CompletableFuture.completedFuture(null);
        }
        // Look up the crafting step for this ingredient.
        CraftingStep step = stepLookup.get(ingredientToResolve);
        // Base Case 2: No recipe exists for this ingredient. Treat it as a raw material to be requested.
        if (step == null) {
            logger.info("[Resolver] Base Case (No Recipe): Adding " + quantityNeeded + "x " + ingredientToResolve
                    + " to shopping list.");
            concreteShoppingList.merge(ingredientToResolve, quantityNeeded, Integer::sum);
            return CompletableFuture.completedFuture(null);
        }
        // Recursive Step: Choose the best path and resolve its dependencies.
        return chooseBestPath(step, agent, stepLookup, nearbyAvailableMaterials, calculator)
                .thenCompose(chosenPath -> {
                    if (chosenPath == null) {
                        // This should not happen if a step exists, but is a safe fallback.
                        logger.warning("[Resolver] Could not choose a path for " + ingredientToResolve + ". Treating as raw material.");
                        concreteShoppingList.merge(ingredientToResolve, quantityNeeded, Integer::sum);
                        return CompletableFuture.completedFuture(null);
                    }
                    logger.info("[Resolver] For " + quantityNeeded + "x " + ingredientToResolve + ", chose path with requirements: "
                            + chosenPath.requirements());
                    // Calculate how many times we need to perform this craft.
                    int craftsToPerform = (int) Math.ceil((double) quantityNeeded / chosenPath.yield());
                    // Create a list of futures for all sub-dependencies.
                    List<CompletableFuture<Void>> childFutures = new ArrayList<>();
                    for (Map.Entry<Ingredient, Integer> requirement : chosenPath.requirements().entrySet()) {
                        Ingredient subIngredient = requirement.getKey();
                        int subQuantityNeeded = requirement.getValue() * craftsToPerform;
                        childFutures.add(resolveIngredient(subIngredient, subQuantityNeeded, stepLookup, agent,
                                nearbyAvailableMaterials, calculator));
                    }
                    // Return a future that completes when all child dependencies have been resolved.
                    return CompletableFuture.allOf(childFutures.toArray(new CompletableFuture[0]));
                });
    }

    /**
     * Applies a cost-based heuristic to select the most efficient crafting path.
     */
    private CompletableFuture<CraftingPath> chooseBestPath(CraftingStep step, NerrusAgent agent,
            Map<Ingredient, CraftingStep> stepLookup, Set<Material> nearbyAvailableMaterials, MemoizedCostCalculator calculator) {
        List<CompletableFuture<Map.Entry<CraftingPath, Integer>>> scoredPathFutures = new ArrayList<>();
        for (CraftingPath path : step.paths()) {
            CompletableFuture<Integer> totalPathCost = CompletableFuture.completedFuture(0);
            for (Ingredient requirement : path.requirements().keySet()) {
                // Accumulate the costs of all sub-ingredients for this path.
                totalPathCost = totalPathCost.thenCombine(calculator.calculateCost(requirement), Integer::sum);
            }
            scoredPathFutures.add(totalPathCost.thenApply(cost -> Map.entry(path, cost)));
        }
        return CompletableFuture.allOf(scoredPathFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> scoredPathFutures.stream()
                        .map(CompletableFuture::join)
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null));
    }

    /**
     * Compares the concrete shopping list against the agent's inventory to determine what is missing.
     */
    private CompletableFuture<Map<Ingredient, Integer>> determineMissingMaterials(NerrusAgent agent,
            Map<Ingredient, Integer> concreteShoppingList) {
        Set<Material> allRequiredMaterials = concreteShoppingList.keySet().stream()
                .flatMap(ing -> ing.materials().stream())
                .collect(Collectors.toSet());
        return new CountItemsSkill(allRequiredMaterials).execute(agent).thenApply(inventory -> {
            Map<Ingredient, Integer> missing = new HashMap<>();
            for (Map.Entry<Ingredient, Integer> required : concreteShoppingList.entrySet()) {
                Ingredient ingredient = required.getKey();
                int requiredAmount = required.getValue();
                // Sum up all materials in the inventory that match this ingredient.
                int ownedAmount = ingredient.materials().stream()
                        .mapToInt(mat -> inventory.getOrDefault(mat, 0))
                        .sum();
                if (ownedAmount < requiredAmount) {
                    missing.put(ingredient, requiredAmount - ownedAmount);
                }
            }
            return missing;
        });
    }

    /**
     * The main control loop for acquiring materials. It repeatedly checks what's missing and acquires
     * one type of ingredient at a time until the shopping list is satisfied.
     */
    private CompletableFuture<Void> acquisitionLoop(NerrusAgent agent, Set<Material> nearbyAvailableMaterials) {
        if (cancelled) {
            return CompletableFuture.completedFuture(null);
        }
        // Step 1: Re-evaluate what's missing from the concrete plan.
        return determineMissingMaterials(agent, concreteShoppingList)
                .thenCompose(missingMaterials -> {
                    if (cancelled) {
                        return CompletableFuture.completedFuture(null);
                    }
                    // Step 2: Base Case: Nothing is missing, we are done.
                    if (missingMaterials.isEmpty()) {
                        logger.info("AcquireMaterialsTask: All materials acquired. Task complete.");
                        return CompletableFuture.completedFuture(null);
                    }
                    // Step 3: Select one ingredient type to acquire.
                    Map.Entry<Ingredient, Integer> entry = missingMaterials.entrySet().iterator().next();
                    Ingredient ingredientToAcquire = entry.getKey();
                    int amountNeeded = entry.getValue();
                    Material representativeMaterial = ingredientToAcquire.getPreferredMaterial();
                    CompletableFuture<?> singleAcquisitionFuture;
                    // Step 4: Decide whether to gather or request the ingredient.
                    if (blockTagManager.isGatherable(representativeMaterial) &&
                            !Collections.disjoint(ingredientToAcquire.materials(), nearbyAvailableMaterials)) {
                        // Gather the full required amount of this specific ingredient.
                        singleAcquisitionFuture = gatherIngredient(agent, ingredientToAcquire, amountNeeded, nearbyAvailableMaterials,
                                new HashSet<>());
                    } else {
                        // Request the item from the player.
                        logger.info("AcquireMaterialsTask: Requesting " + amountNeeded + " " + representativeMaterial);
                        singleAcquisitionFuture = new RequestItemSkill(representativeMaterial,
                                concreteShoppingList.get(ingredientToAcquire))
                                        .execute(agent)
                                        .thenAccept(success -> {
                                            if (!success) {
                                                throw new RuntimeException("Player did not provide " + representativeMaterial);
                                            }
                                        });
                    }

                    // Step 5: After acquiring one type of material, loop back to re-evaluate for the next.
                    return singleAcquisitionFuture.thenCompose(v -> acquisitionLoop(agent, nearbyAvailableMaterials));
                });
    }

    /**
     * Recursively gathers a specific quantity of an ingredient by repeatedly executing GatherBlockTask.
     * This method is the sub-loop for handling the quantity of a single material type.
     *
     * @param agent
     *            The agent performing the action.
     * @param ingredient
     *            The ingredient to gather.
     * @param totalNeeded
     *            The total number of this ingredient required.
     * @param attemptedLocations
     *            A set of locations already tried for this ingredient to avoid getting stuck.
     * @return A CompletableFuture that completes when the required quantity is gathered, or fails if it
     *         cannot be.
     */
    private CompletableFuture<Void> gatherIngredient(NerrusAgent agent, Ingredient ingredient, int totalNeeded,
            Set<Material> nearbyAvailableMaterials, Set<Location> attemptedLocations) {
        if (cancelled)
            return CompletableFuture.completedFuture(null);

        // Check how many we have vs. how many we need.
        return new CountItemsSkill(ingredient.materials()).execute(agent)
                .thenCompose(counts -> {
                    int currentAmount = counts.values().stream().mapToInt(Integer::intValue).sum();
                    if (currentAmount >= totalNeeded) {
                        logger.info("AcquireMaterialsTask: Finished gathering " + ingredient + ".");
                        return CompletableFuture.completedFuture(null); // Base case: we have enough.
                    }
                    logger.info(
                            "AcquireMaterialsTask: Gathering one " + ingredient + " (" + (currentAmount + 1) + "/" + totalNeeded + ").");
                    // This is the "gate" future that waits for the agent's task to finish.
                    CompletableFuture<GatherResult> gate = new CompletableFuture<>();
                    Set<Material> materialsToGather = new HashSet<>(ingredient.materials());
                    materialsToGather.retainAll(nearbyAvailableMaterials);
                    // This is the temporary wrapper Task that executes the real task and completes the gate.
                    Task wrapperTask = new Task() {
                        private final Task realTask = new GatherBlockTask(materialsToGather, 50, attemptedLocations);

                        @Override
                        public CompletableFuture<Void> execute(NerrusAgent agent) {
                            CompletableFuture<Void> future = realTask.execute(agent);
                            future.whenComplete((v, ex) -> {
                                if (ex != null) {
                                    gate.completeExceptionally(ex);
                                    return;
                                }
                                GatherResult result = agent.getBlackboard().getEnum(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.class)
                                        .orElse(GatherResult.FAILED_TO_COLLECT);
                                agent.getBlackboard().remove(BlackboardKeys.GATHER_BLOCK_RESULT);
                                gate.complete(result);
                            });
                            return future;
                        }

                        @Override
                        public void cancel() {
                            realTask.cancel();
                            gate.cancel(true);
                        }
                    };
                    IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                        if (!cancelled) { // Check for cancellation again before scheduling
                            agent.setCurrentTask(wrapperTask);
                        }
                    });
                    // Chain the next action off the gate future.
                    return gate.thenCompose(result -> {
                        if (result == GatherResult.SUCCESS) {
                            // Success, recurse to gather the next one.
                            return gatherIngredient(agent, ingredient, totalNeeded, nearbyAvailableMaterials, attemptedLocations);
                        } else {
                            // Failure, fail the entire acquisition process.
                            return CompletableFuture
                                    .failedFuture(new RuntimeException("Failed to gather block for " + ingredient + ". Reason: " + result));
                        }
                    });
                });
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * A helper class to calculate the "acquisition cost" of ingredients recursively.
     * It uses a memoization cache to avoid re-computing costs for the same ingredient.
     */
    private class MemoizedCostCalculator {
        private final Map<Ingredient, CompletableFuture<Integer>> memo = new HashMap<>();
        private final NerrusAgent agent;
        private final Map<Ingredient, CraftingStep> stepLookup;
        private final Set<Material> nearbyAvailableMaterials;

        MemoizedCostCalculator(NerrusAgent agent, Map<Ingredient, CraftingStep> stepLookup, Set<Material> nearbyAvailableMaterials) {
            this.agent = agent;
            this.stepLookup = stepLookup;
            this.nearbyAvailableMaterials = nearbyAvailableMaterials;
        }

        /**
         * Recursively calculates the cost to acquire an ingredient.
         */
        public CompletableFuture<Integer> calculateCost(Ingredient ingredient) {
            // Return cached result if available to prevent re-computation.
            if (memo.containsKey(ingredient)) {
                return memo.get(ingredient);
            }
            CompletableFuture<Integer> costFuture = new CountItemsSkill(ingredient.materials()).execute(agent)
                    .thenCompose(inventory -> {
                        // Cost 0: Already have it in inventory.
                        if (inventory.values().stream().mapToInt(Integer::intValue).sum() > 0) {
                            return CompletableFuture.completedFuture(0);
                        }
                        // Cost 1 or 3: It's a gatherable raw material.
                        if (blockTagManager.isGatherable(ingredient.getPreferredMaterial())) {
                            // Cost 1: It's nearby.
                            if (!Collections.disjoint(ingredient.materials(), nearbyAvailableMaterials)) {
                                return CompletableFuture.completedFuture(1);
                            }
                            // Cost 3: It's gatherable but must be searched for.
                            return CompletableFuture.completedFuture(3);
                        }
                        // It's not in inventory and not gatherable, so it's either craftable or must be requested.
                        CraftingStep step = stepLookup.get(ingredient);
                        // Cost 4: No recipe exists. It must be requested.
                        if (step == null) {
                            return CompletableFuture.completedFuture(4);
                        }
                        // Recursive Step: It's craftable. Find the minimum cost of all possible crafting paths.
                        List<CompletableFuture<Integer>> pathCostFutures = new ArrayList<>();
                        for (CraftingPath path : step.paths()) {
                            CompletableFuture<Integer> totalPathCost = CompletableFuture.completedFuture(0);
                            for (Ingredient subIngredient : path.requirements().keySet()) {
                                // Recursively calculate cost for each sub-ingredient and add it to this path's total.
                                totalPathCost = totalPathCost.thenCombine(calculateCost(subIngredient), Integer::sum);
                            }
                            pathCostFutures.add(totalPathCost);
                        }
                        // Return the minimum cost from all possible paths.
                        return CompletableFuture.allOf(pathCostFutures.toArray(new CompletableFuture[0]))
                                .thenApply(v -> pathCostFutures.stream()
                                        .map(CompletableFuture::join) // Safely get results after allOf completes
                                        .filter(Objects::nonNull)
                                        .min(Integer::compareTo)
                                        .orElse(Integer.MAX_VALUE)); // If no paths are valid, assign max cost.
                    });

            memo.put(ingredient, costFuture);
            return costFuture;
        }
    }
}