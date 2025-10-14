package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.Ingredient;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalPrerequisite;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.helpers.CraftingContext;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;

import org.bukkit.Material;
import org.bukkit.inventory.CraftingRecipe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CraftItemGoal implements Goal {
    private enum State {
        PLANNING, ACQUIRING_MATERIALS, ENSURING_STATION, PREPARING_CRAFT, EXECUTING_CRAFT, COMPLETED, FAILED
    }

    private final CraftItemParameters params;
    private final RecipeService recipeService;
    private final TaskFactory taskFactory;
    private final Logger logger;

    private boolean isFinished = false;
    private int initialTargetItemCount = 0;
    private int ensuringStationStep = 0;
    private State state = State.PLANNING;
    private GoalResult finalResult;
    private Material targetItemMaterial;
    private Map<Ingredient, Integer> neededCache;
    private Map<Ingredient, Integer> demandCache;

    public CraftItemGoal(CraftItemParameters params, RecipeService recipeService, BlockTagManager blockTagManager,
            TaskFactory taskFactory) {
        this.params = params;
        this.recipeService = recipeService;
        this.taskFactory = taskFactory;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public void start(NerrusAgent agent) {
        try {
            this.targetItemMaterial = Material.valueOf(params.itemName().toUpperCase());
        } catch (IllegalArgumentException e) {
            fail("I do not know how to craft an item named '" + params.itemName() + "'.");
            return;
        }
        agent.speak("Let me see... to craft " + params.quantity() + " " + params.itemName() + ", I'll need a plan.");
        process(agent);
    }

    @Override
    public void resume(NerrusAgent agent, GoalResult subGoalResult) {
        if (subGoalResult instanceof GoalResult.Failure failure) {
            fail("I couldn't get what I needed. " + failure.message());
            return;
        }
        this.isFinished = false;
        this.finalResult = null;
        // If we were trying to get a crafting table, we should now re-check for one.
        if (this.state == State.ENSURING_STATION) {
            agent.speak("Okay, I have what I need to make a crafting table. Let's try this again.");
            this.state = State.PREPARING_CRAFT; // Go back to preparation to re-trigger the station check.
            this.ensuringStationStep = 0;
        } else {
            this.state = State.ACQUIRING_MATERIALS;
            agent.speak("Alright, let's see what's next.");
        }
        process(agent);
    }

    @Override
    public void process(NerrusAgent agent) {
        if (isFinished() || agent.getCurrentTask() != null) {
            return;
        }
        switch (state) {
            case PLANNING:
                agent.setCurrentTask(createPlanningTask());
                break;
            case ACQUIRING_MATERIALS:
                handleAcquisitionState(agent);
                break;
            case ENSURING_STATION:
                handleEnsuringStation(agent);
                break;
            case PREPARING_CRAFT:
                handleCraftPreparation(agent);
                break;
            case EXECUTING_CRAFT:
                agent.setCurrentTask(createCraftExecutionTask(agent));
                break;
            default:
                break;
        }
    }

    private void handleAcquisitionState(NerrusAgent agent) {
        if (agent.getBlackboard().has(BlackboardKeys.NEXT_PREREQUISITE)) {
            GoalPrerequisite prereq = agent.getBlackboard().get(BlackboardKeys.NEXT_PREREQUISITE, GoalPrerequisite.class).get();
            agent.getBlackboard().remove(BlackboardKeys.NEXT_PREREQUISITE);
            declarePrerequisite("I need to acquire a prerequisite: " + prereq.goalName(), prereq);
        } else if (agent.getBlackboard().getBoolean(BlackboardKeys.MATERIALS_ACQUIRED, false)) {
            agent.getBlackboard().remove(BlackboardKeys.MATERIALS_ACQUIRED);
            agent.setCurrentTask(createContextCreationTask());
        } else {
            RecipeService.CraftingBlueprint plan = agent.getBlackboard()
                    .get(BlackboardKeys.CRAFTING_BLUEPRINT, RecipeService.CraftingBlueprint.class)
                    .orElseThrow(() -> new IllegalStateException("Crafting plan missing from blackboard."));
            Task task = taskFactory.createTask("ACQUIRE_MATERIALS", Map.of(
                    "plan", plan,
                    "targetIngredient", Ingredient.of(targetItemMaterial),
                    "targetQuantity", params.quantity()));
            agent.setCurrentTask(task);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleCraftPreparation(NerrusAgent agent) {
        // This is a synchronous block of logic that decides the next craft
        CraftingContext context = agent.getBlackboard().get(BlackboardKeys.CRAFTING_CONTEXT, CraftingContext.class)
                .orElseThrow(() -> new IllegalStateException("Crafting context missing from blackboard."));
        RecipeService.CraftingBlueprint plan = agent.getBlackboard()
                .get(BlackboardKeys.CRAFTING_BLUEPRINT, RecipeService.CraftingBlueprint.class)
                .orElseThrow(() -> new IllegalStateException("Crafting plan missing from blackboard."));
        Map<Ingredient, RecipeService.CraftingPath> executionPlan = (Map<Ingredient, RecipeService.CraftingPath>) agent.getBlackboard()
                .get(BlackboardKeys.CRAFTING_EXECUTION_PLAN, Map.class)
                .orElseThrow(() -> new IllegalStateException("Resolved crafting plan missing from blackboard."));
        // Decoupling Demand from Deficit: Initialize both caches for this round of calculations.
        this.neededCache = new HashMap<>();
        this.demandCache = new HashMap<>();
        // Check if we have enough of the final product
        if (context.getAvailableCount(Ingredient.of(targetItemMaterial)) >= initialTargetItemCount + params.quantity()) {
            succeed("I have successfully crafted " + params.quantity() + " " + params.itemName() + ".");
            return;
        }
        // Find the next craftable step in the plan
        for (RecipeService.CraftingStep step : plan.craftingSteps()) {
            // Use the new, accurate, inventory-aware demand calculation.
            int needed = calculateNeeded(step.ingredientToCraft(), plan, context, executionPlan);
            if (needed <= 0) {
                continue; // We have enough of this intermediate item
            }
            RecipeService.CraftingPath pathToCraft = executionPlan.get(step.ingredientToCraft());
            if (pathToCraft == null) {
                // This shouldn't happen if the plan is consistent, but it's a good safeguard.
                continue;
            }
            if (hasIngredientsForPath(pathToCraft, context)) {
                if (RecipeService.is3x3Recipe(pathToCraft.recipes().get(0))
                        && !agent.getBlackboard().has(BlackboardKeys.CRAFTING_TABLE_LOCATION)) {
                    agent.speak("I'll need a crafting table for this.");
                    this.state = State.ENSURING_STATION;
                    this.ensuringStationStep = 0;
                    process(agent); // Immediately re-process to enter the new state logic.
                    return;
                }
                int craftsToPerform = (int) Math.ceil((double) needed / pathToCraft.yield());
                agent.getBlackboard().put("craft.recipe", pathToCraft.recipes().get(0)); // Use first recipe in path
                agent.getBlackboard().put("craft.times", craftsToPerform);
                this.state = State.EXECUTING_CRAFT;
                process(agent); // Immediately dispatch the task
                return;
            }
        }
        // Instead of failing, transition back to ACQUIRING_MATERIALS. This creates a robust loop
        // that allows the agent to gather more intermediate resources if it runs out mid-craft.
        // If we loop through and find nothing to craft, but haven't met the goal,
        // it means we ran out of an intermediate material. We must re-acquire.
        logger.info("Stuck in craft preparation. Re-evaluating material needs.");
        this.state = State.ACQUIRING_MATERIALS;
        process(agent); // Immediately re-process to enter the acquisition state.
    }

    private void handleEnsuringStation(NerrusAgent agent) {
        switch (ensuringStationStep) {
            case 0: // Find a nearby table
                agent.setCurrentTask(taskFactory.createTask("FIND_NEARBY_BLOCK", Map.of("material", Material.CRAFTING_TABLE)));
                ensuringStationStep++;
                break;
            case 1: // Check if we found one
                if (agent.getBlackboard().has(BlackboardKeys.CRAFTING_TABLE_LOCATION)) {
                    this.state = State.PREPARING_CRAFT; // Found one, go back to prepare the craft.
                    process(agent);
                } else {
                    ensuringStationStep++; // Didn't find one, check inventory.
                    process(agent);
                }
                break;
            case 2: // Check inventory for a table
                agent.setCurrentTask(createCountCraftingTableTask());
                ensuringStationStep++;
                break;
            case 3: // Decide whether to place or craft a table
                int count = agent.getBlackboard().getInt("crafting.tableCount", 0);
                agent.getBlackboard().remove("crafting.tableCount");
                if (count > 0) {
                    agent.setCurrentTask(taskFactory.createTask("PLACE_BLOCK", Map.of("material", Material.CRAFTING_TABLE)));
                    ensuringStationStep++; // Move to step 4 to check placement result.
                } else {
                    // No table in inventory, must craft one.
                    declarePrerequisite("I need to craft a crafting table first.",
                            new GoalPrerequisite("CRAFT_ITEM", new CraftItemParameters("CRAFTING_TABLE", 1)));
                }
                break;
            case 4: // Check result of placement
                if (agent.getBlackboard().has(BlackboardKeys.CRAFTING_TABLE_LOCATION)) {
                    this.state = State.PREPARING_CRAFT; // Placed one, go back to prepare the craft.
                    process(agent);
                } else {
                    fail("I have a crafting table, but I couldn't place it.");
                }
                break;
        }
    }

    private Task createCountCraftingTableTask() {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                return new CountItemsSkill(Set.of(Material.CRAFTING_TABLE)).execute(agent)
                        .thenAccept(counts -> {
                            agent.getBlackboard().put("crafting.tableCount", counts.getOrDefault(Material.CRAFTING_TABLE, 0));
                        });
            }

            @Override
            public void cancel() {
            }
        };
    }

    private Task createPlanningTask() {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                return CompletableFuture.runAsync(() -> {
                    Optional<RecipeService.CraftingBlueprint> planOpt = recipeService.createCraftingPlan(targetItemMaterial,
                            params.quantity());
                    IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                        if (planOpt.isEmpty()) {
                            fail("I was unable to create a crafting plan for " + params.itemName() + ".");
                            return;
                        }
                        RecipeService.CraftingBlueprint plan = planOpt.get();
                        String stepsStr = plan.craftingSteps().stream()
                                .map(s -> s.ingredientToCraft().toString())
                                .collect(Collectors.joining(" -> "));
                        logger.info("Finalized Crafting Plan: Raw Materials: " + plan.rawIngredients() + " | Steps: " + stepsStr);
                        agent.getBlackboard().put(BlackboardKeys.CRAFTING_BLUEPRINT, plan);
                        state = State.ACQUIRING_MATERIALS;
                    });
                }, IonNerrus.getInstance().getOffloadThreadExecutor());
            }

            @Override
            public void cancel() {
            }
        };
    }

    private Task createCraftExecutionTask(NerrusAgent agent) {
        CraftingRecipe recipe = agent.getBlackboard().get("craft.recipe", CraftingRecipe.class)
                .orElseThrow(() -> new IllegalStateException("Missing craft.recipe on blackboard"));
        int timesToCraft = agent.getBlackboard().get("craft.times", Integer.class)
                .orElseThrow(() -> new IllegalStateException("Missing craft.times on blackboard"));
        CraftingContext context = agent.getBlackboard().get(BlackboardKeys.CRAFTING_CONTEXT, CraftingContext.class)
                .orElseThrow(() -> new IllegalStateException("Missing crafting context on blackboard."));
        // After execution, loop back to preparing for the next craft.
        this.state = State.PREPARING_CRAFT;
        return taskFactory.createTask("EXECUTE_CRAFT", Map.of(
                "recipe", recipe,
                "timesToCraft", timesToCraft,
                "context", context));
    }

    /**
     * Recursively calculates the total number of a target ingredient required by the full crafting
     * plan, irrespective of current inventory. This is the "strategic" calculation.
     */
    private int calculateTotalDemand(Ingredient target, RecipeService.CraftingBlueprint plan,
            Map<Ingredient, RecipeService.CraftingPath> resolvedPlan) {
        // Use memoization to avoid re-calculating for the same ingredient.
        if (demandCache.containsKey(target)) {
            return demandCache.get(target);
        }
        int totalDemand;
        // Base Case: If the target is the final item, demand is the total we need to have at the end.
        if (target.materials().contains(this.targetItemMaterial)) {
            totalDemand = this.initialTargetItemCount + this.params.quantity();
        } else {
            // Recursive Step: Sum the demand from all parent items that require this 'target'.
            totalDemand = 0;
            for (RecipeService.CraftingStep parentStep : plan.craftingSteps()) {
                RecipeService.CraftingPath chosenPath = resolvedPlan.get(parentStep.ingredientToCraft());
                if (chosenPath == null || !chosenPath.requirements().containsKey(target)) {
                    continue; // This parent doesn't use our target ingredient in its chosen path.
                }
                // RECURSIVE CALL: Find the total demand for the parent item.
                int parentTotalDemand = calculateTotalDemand(parentStep.ingredientToCraft(), plan, resolvedPlan);
                if (parentTotalDemand > 0) {
                    // How many times do we need to perform the parent craft to meet its total demand?
                    // Note: We do not subtract inventory here. This is a pure calculation on the recipe graph.
                    int craftsToPerform = (int) Math.ceil((double) parentTotalDemand / chosenPath.yield());
                    // Add the resulting demand for our target ingredient.
                    totalDemand += craftsToPerform * chosenPath.requirements().get(target);
                }
            }
        }
        demandCache.put(target, totalDemand);
        return totalDemand;
    }

    /**
     * Calculates the net number of items needed for a step by comparing the total strategic demand
     * with the current tactical inventory. This is the "tactical" calculation.
     */
    private int calculateNeeded(Ingredient target, RecipeService.CraftingBlueprint plan, CraftingContext context,
            Map<Ingredient, RecipeService.CraftingPath> resolvedPlan) {
        if (neededCache.containsKey(target)) {
            return neededCache.get(target);
        }
        // Step 1: Get the total strategic demand for the item.
        int totalDemand = calculateTotalDemand(target, plan, resolvedPlan);
        // Step 2: Get the current amount available in our virtual inventory.
        int ownedAmount = context.getAvailableCount(target);
        // Step 3: The deficit is the difference.
        int needed = Math.max(0, totalDemand - ownedAmount);
        neededCache.put(target, needed);
        return needed;
    }

    private boolean hasIngredientsForPath(RecipeService.CraftingPath path, CraftingContext context) {
        for (Map.Entry<Ingredient, Integer> requirement : path.requirements().entrySet()) {
            if (context.getAvailableCount(requirement.getKey()) < requirement.getValue()) {
                return false;
            }
        }
        return true;
    }

    private Task createContextCreationTask() {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                RecipeService.CraftingBlueprint plan = agent.getBlackboard()
                        .get(BlackboardKeys.CRAFTING_BLUEPRINT, RecipeService.CraftingBlueprint.class)
                        .orElseThrow(() -> new IllegalStateException("Crafting plan missing from blackboard for context creation."));
                // Collect all materials that could possibly be involved to get a full inventory snapshot.
                Set<Material> allRelevantMaterials = new HashSet<>();
                plan.rawIngredients().keySet().forEach(ing -> allRelevantMaterials.addAll(ing.materials()));
                plan.craftingSteps().forEach(step -> {
                    allRelevantMaterials.addAll(step.ingredientToCraft().materials());
                    step.paths().forEach(
                            path -> path.requirements().keySet().forEach(ing -> allRelevantMaterials.addAll(ing.materials())));
                });
                return new CountItemsSkill(allRelevantMaterials).execute(agent)
                        .thenAccept(inventoryCount -> {
                            CraftingContext context = new CraftingContext(inventoryCount);
                            agent.getBlackboard().put(BlackboardKeys.CRAFTING_CONTEXT, context);
                            state = State.PREPARING_CRAFT;
                            initialTargetItemCount = context.getAvailableCount(Ingredient.of(targetItemMaterial));
                            logger.info("CraftingContext created. Initial target item count: " + initialTargetItemCount
                                    + ". Transitioning to PREPARING_CRAFT.");
                        });
            }

            @Override
            public void cancel() {
                // No-op, skill is short-lived.
            }
        };
    }

    private void declarePrerequisite(String reason, GoalPrerequisite prerequisite) {
        if (isFinished())
            return;
        this.finalResult = new GoalResult.PrerequisiteResult(reason, prerequisite);
        this.isFinished = true;
    }

    private void succeed(String message) {
        if (isFinished()) {
            return;
        }
        this.finalResult = new GoalResult.Success(message);
        this.state = State.COMPLETED;
        this.isFinished = true;
    }

    private void fail(String message) {
        if (isFinished()) {
            return;
        }
        logger.severe("CraftItemGoal Failed: " + message);
        this.finalResult = new GoalResult.Failure(message);
        this.state = State.FAILED;
        this.isFinished = true;
    }

    @Override
    public boolean isFinished() {
        return isFinished;
    }

    @Override
    public void stop(NerrusAgent agent) {
        if (!isFinished()) {
            fail("Crafting goal was cancelled.");
        }
    }

    @Override
    public GoalResult getFinalResult() {
        if (finalResult == null) {
            return new GoalResult.Failure("Goal finished without a result, likely due to cancellation.");
        }
        return finalResult;
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "CRAFT_ITEM",
                    "Crafts items from materials. This is a high-level tool that will automatically gather required raw materials and perform all necessary intermediate crafting steps.",
                    CraftItemParameters.class);
        }
    }
}