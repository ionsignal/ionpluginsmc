package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.Ingredient;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.helpers.CraftingContext;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;

import org.bukkit.Material;
import org.bukkit.inventory.CraftingRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CraftItemGoal implements Goal {
    private enum State {
        PLANNING, ACQUIRING_MATERIALS, PREPARING_NEXT_CRAFT_STEP, ENSURING_CRAFTING_STATION, EXECUTING_CRAFT, COMPLETED, FAILED
    }

    private final CraftItemParameters params;
    private final RecipeService recipeService;
    private final TaskFactory taskFactory;
    private final Logger logger;

    private State state = State.PLANNING;
    private GoalResult finalResult;
    private RecipeService.CraftingPlan craftingPlan;
    private Deque<RecipeService.CraftingStep> craftingSteps;
    private CraftingContext craftingContext;
    private Material targetMaterial;
    private Map<Ingredient, Integer> demandMap;

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
            this.targetMaterial = Material.valueOf(params.itemName().toUpperCase());
        } catch (IllegalArgumentException e) {
            fail("I do not know how to craft an item named '" + params.itemName() + "'.");
            return;
        }
        agent.speak("Planning how to craft " + params.quantity() + " " + params.itemName() + ".");
    }

    @Override
    public void process(NerrusAgent agent) {
        if (isFinished() || agent.getCurrentTask() != null) {
            return;
        }
        Task nextTask = null;
        switch (state) {
            case PLANNING:
                nextTask = createPlanningTask();
                break;

            case ACQUIRING_MATERIALS:
                agent.speak("I need to gather some materials first.");
                nextTask = taskFactory.createTask("ACQUIRE_MATERIALS", Map.of(
                        "plan", this.craftingPlan,
                        "targetIngredient", Ingredient.of(this.targetMaterial),
                        "targetQuantity", params.quantity()));
                state = State.PREPARING_NEXT_CRAFT_STEP;
                break;

            case PREPARING_NEXT_CRAFT_STEP:
                // This is a synchronous action that might change the state and allow another
                // case to be hit in the same tick if no task is dispatched.
                prepareNextCraftStep(agent);
                break;

            case ENSURING_CRAFTING_STATION:
                nextTask = taskFactory.createTask("ENSURE_CRAFTING_STATION", Map.of());
                state = State.EXECUTING_CRAFT; // Optimistically transition
                break;

            case EXECUTING_CRAFT:
                // This state is entered after preparation logic has run.
                // It dispatches the task that actually performs the craft loop.
                prepareAndExecuteCraft(agent); // This method will set the next task if needed.
                break;

            default:
                break;
        }
        if (nextTask != null) {
            agent.setCurrentTask(nextTask);
        } else if (!isFinished() && agent.getCurrentTask() == null) {
            // If a state transition happened but no task was dispatched (e.g., after prep),
            // re-run process to immediately handle the new state.
            process(agent);
        }
    }

    private void prepareNextCraftStep(NerrusAgent agent) {
        // First-time entry after acquisition: retrieve the context from the blackboard.
        if (this.craftingContext == null) {
            Optional<CraftingContext> contextOpt = agent.getBlackboard().get(BlackboardKeys.CRAFTING_CONTEXT, CraftingContext.class);
            if (contextOpt.isEmpty()) {
                fail("Material acquisition failed, so I cannot proceed with crafting.");
                return;
            }
            this.craftingContext = contextOpt.get();
            agent.getBlackboard().remove(BlackboardKeys.CRAFTING_CONTEXT);
        }
        // Check for final completion.
        if (craftingContext.getAvailableCount(Ingredient.of(targetMaterial)) >= params.quantity()) {
            succeed("I have successfully crafted " + params.quantity() + " " + params.itemName() + ".");
            return;
        }
        // Process the next step in the plan.
        while (!craftingSteps.isEmpty()) {
            // First, get the total demand for this intermediate item from our pre-calculated map. Then,
            // calculate how many more we need to make based on what's already available in the context.
            RecipeService.CraftingStep currentStep = craftingSteps.peek(); // Peek, don't remove yet.
            int totalDemand = this.demandMap.getOrDefault(currentStep.ingredientToCraft(), 0);
            int needed = totalDemand - craftingContext.getAvailableCount(currentStep.ingredientToCraft());
            if (needed <= 0) {
                craftingSteps.poll(); // We have enough of this intermediate, discard step and check the next.
                continue;
            }
            // Find a usable recipe from the available paths based on our context.
            Optional<CraftingRecipe> usableRecipeOpt = findUsableRecipe(currentStep);
            if (usableRecipeOpt.isPresent()) {
                CraftingRecipe recipeToExecute = usableRecipeOpt.get();
                int yield = recipeToExecute.getResult().getAmount();
                int craftsToPerform = (int) Math.ceil((double) needed / yield);
                agent.getBlackboard().put("craft.recipe", recipeToExecute);
                agent.getBlackboard().put("craft.times", craftsToPerform);
                logger.info("Next action: Perform " + craftsToPerform + " craft(s) for " + currentStep.ingredientToCraft());
                if (RecipeService.is3x3Recipe(recipeToExecute)) {
                    state = State.ENSURING_CRAFTING_STATION;
                } else {
                    state = State.EXECUTING_CRAFT;
                }
                return; // Exit to let process() handle the new state.
            } else {
                fail("I have a plan to craft " + currentStep.ingredientToCraft()
                        + ", but I'm missing the specific materials required for it.");
                return;
            }
        }
        // If the queue is empty but we still haven't met the final goal, something is wrong.
        if (craftingContext.getAvailableCount(Ingredient.of(targetMaterial)) < params.quantity()) {
            fail("I completed all crafting steps, but still do not have enough " + params.itemName() + ".");
        } else {
            // This can happen if the last craft satisfied the goal.
            succeed("I have successfully crafted " + params.quantity() + " " + params.itemName() + ".");
        }
    }

    private void prepareAndExecuteCraft(NerrusAgent agent) {
        Optional<CraftingRecipe> recipeOpt = agent.getBlackboard().get("craft.recipe", CraftingRecipe.class);
        int timesToCraft = agent.getBlackboard().getInt("craft.times", 0);
        if (recipeOpt.isEmpty() || timesToCraft <= 0) {
            fail("Internal error: Tried to execute a craft without a valid recipe or quantity.");
            return;
        }
        // If it's a 3x3 recipe, we must have a table location from the previous state.
        if (RecipeService.is3x3Recipe(recipeOpt.get())) {
            if (!agent.getBlackboard().has(BlackboardKeys.CRAFTING_TABLE_LOCATION)) {
                // Here is where we would insert the sub-goal to craft a table. For now, we fail.
                fail("I need a crafting table to proceed, but I could not find or place one.");
                return;
            }
        }
        Task craftTask = taskFactory.createTask("EXECUTE_CRAFT", Map.of(
                "recipe", recipeOpt.get(),
                "timesToCraft", timesToCraft,
                "context", this.craftingContext));
        // After this craft execution, we must re-evaluate the next step.
        state = State.PREPARING_NEXT_CRAFT_STEP;
        agent.setCurrentTask(craftTask);
    }

    private Optional<CraftingRecipe> findUsableRecipe(RecipeService.CraftingStep step) {
        return step.paths().stream()
                .flatMap(path -> path.recipes().stream())
                .filter(craftingContext::hasIngredientsFor)
                .findFirst();
    }

    private Task createPlanningTask() {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                return CompletableFuture.runAsync(() -> {
                    Optional<RecipeService.CraftingPlan> planOpt = recipeService.createCraftingPlan(targetMaterial, params.quantity());
                    IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                        if (planOpt.isEmpty()) {
                            fail("I was unable to create a crafting plan for " + params.itemName() + ".");
                            return;
                        }
                        craftingPlan = planOpt.get();
                        craftingSteps = new LinkedList<>(craftingPlan.craftingSteps());
                        demandMap = calculateFullDemandMap();
                        String stepsStr = craftingPlan.craftingSteps().stream()
                                .map(s -> s.ingredientToCraft().toString())
                                .collect(Collectors.joining(" -> "));
                        logger.info("Finalized Crafting Plan: Raw Materials: " + craftingPlan.rawIngredients() + " | Steps: " + stepsStr
                                + " | Demand Map: " + demandMap);
                        state = State.ACQUIRING_MATERIALS;
                    });
                }, IonNerrus.getInstance().getOffloadThreadExecutor());
            }

            @Override
            public void cancel() {
            }
        };
    }

    /**
     * Calculates the total number of each intermediate and final item that needs to be produced to
     * satisfy the final goal. It works backwards from the final product through the dependency tree.
     *
     * @return A map where the key is the ingredient to be crafted and the value is the total quantity
     *         required.
     */
    private Map<Ingredient, Integer> calculateFullDemandMap() {
        Map<Ingredient, Integer> fullDemandMap = new HashMap<>();
        // Seed the map with the final target item and quantity.
        fullDemandMap.put(Ingredient.of(this.targetMaterial), this.params.quantity());
        // Create a reversed list of the crafting steps to process dependencies from end to start.
        List<RecipeService.CraftingStep> reversedSteps = new ArrayList<>(this.craftingSteps);
        Collections.reverse(reversedSteps);
        for (RecipeService.CraftingStep step : reversedSteps) {
            Ingredient product = step.ingredientToCraft();
            int amountNeeded = fullDemandMap.getOrDefault(product, 0);
            if (amountNeeded > 0) {
                // To determine the requirements, we assume the first crafting path is representative.
                // This is a safe assumption as different recipes for the same item (e.g., oak vs. birch planks)
                // are structurally identical in terms of ingredient counts.
                if (step.paths().isEmpty()) {
                    continue; // Should not happen for a valid plan.
                }
                RecipeService.CraftingPath representativePath = step.paths().get(0);
                // Calculate how many times this craft needs to be performed.
                int craftsToPerform = (int) Math.ceil((double) amountNeeded / representativePath.yield());
                // Add the required sub-ingredients to the demand map.
                for (Map.Entry<Ingredient, Integer> requirement : representativePath.requirements().entrySet()) {
                    Ingredient requiredIngredient = requirement.getKey();
                    int amountPerCraft = requirement.getValue();
                    fullDemandMap.merge(requiredIngredient, amountPerCraft * craftsToPerform, Integer::sum);
                }
            }
        }
        return fullDemandMap;
    }

    private void succeed(String message) {
        if (isFinished()) {
            return;
        }
        this.finalResult = new GoalResult(GoalResult.Status.SUCCESS, message);
        this.state = State.COMPLETED;
    }

    private void fail(String message) {
        if (isFinished()) {
            return;
        }
        logger.severe("CraftItemGoal Failed: " + message);
        this.finalResult = new GoalResult(GoalResult.Status.FAILURE, message);
        this.state = State.FAILED;
    }

    @Override
    public boolean isFinished() {
        return state == State.COMPLETED || state == State.FAILED;
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
            return new GoalResult(GoalResult.Status.FAILURE, "Goal finished without a result.");
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