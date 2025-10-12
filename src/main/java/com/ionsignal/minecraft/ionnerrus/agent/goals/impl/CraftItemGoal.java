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

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CraftItemGoal implements Goal {
    private enum State {
        PLANNING, ACQUIRING_MATERIALS, PREPARING_CRAFT, EXECUTING_CRAFT, COMPLETED, FAILED
    }

    private final CraftItemParameters params;
    private final RecipeService recipeService;
    private final TaskFactory taskFactory;
    private final Logger logger;

    private State state = State.PLANNING;
    private GoalResult finalResult;
    private Material targetMaterial;
    private boolean isFinished = false;

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
        this.state = State.ACQUIRING_MATERIALS;
        agent.speak("Alright, let's see what's next.");
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
            RecipeService.CraftingPlan plan = agent.getBlackboard().get(BlackboardKeys.CRAFTING_PLAN, RecipeService.CraftingPlan.class)
                    .orElseThrow(() -> new IllegalStateException("Crafting plan missing from blackboard."));
            Task task = taskFactory.createTask("ACQUIRE_MATERIALS", Map.of(
                    "plan", plan,
                    "targetIngredient", Ingredient.of(targetMaterial),
                    "targetQuantity", params.quantity()));
            agent.setCurrentTask(task);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleCraftPreparation(NerrusAgent agent) {
        // This is a synchronous block of logic that decides the next craft
        CraftingContext context = agent.getBlackboard().get(BlackboardKeys.CRAFTING_CONTEXT, CraftingContext.class)
                .orElseThrow(() -> new IllegalStateException("Crafting context missing from blackboard."));
        RecipeService.CraftingPlan plan = agent.getBlackboard().get(BlackboardKeys.CRAFTING_PLAN, RecipeService.CraftingPlan.class)
                .orElseThrow(() -> new IllegalStateException("Crafting plan missing from blackboard."));
        Map<Ingredient, RecipeService.CraftingPath> resolvedPlan = (Map<Ingredient, RecipeService.CraftingPath>) agent.getBlackboard()
                .get(BlackboardKeys.CRAFTING_RESOLVED_PLAN, Map.class)
                .orElseThrow(() -> new IllegalStateException("Resolved crafting plan missing from blackboard."));
        // Check if we have enough of the final product
        if (context.getAvailableCount(Ingredient.of(targetMaterial)) >= params.quantity()) {
            succeed("I have successfully crafted " + params.quantity() + " " + params.itemName() + ".");
            return;
        }
        // Find the next craftable step in the plan
        for (RecipeService.CraftingStep step : plan.craftingSteps()) {
            int needed = calculateNeededForStep(step, plan, context, resolvedPlan);
            if (needed <= 0) {
                continue; // We have enough of this intermediate item
            }
            RecipeService.CraftingPath pathToCraft = resolvedPlan.get(step.ingredientToCraft());
            if (pathToCraft == null) {
                // This shouldn't happen if the plan is consistent, but it's a good safeguard.
                continue;
            }
            if (hasIngredientsForPath(pathToCraft, context)) {
                int craftsToPerform = (int) Math.ceil((double) needed / pathToCraft.yield());
                agent.getBlackboard().put("craft.recipe", pathToCraft.recipes().get(0)); // Use first recipe in path
                agent.getBlackboard().put("craft.times", craftsToPerform);
                this.state = State.EXECUTING_CRAFT;
                process(agent); // Immediately dispatch the task
                return;
            }
        }
        // If we loop through and find nothing to craft, but haven't met the goal, something is wrong.
        fail("I have all the materials, but I'm not sure how to craft the next step.");
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
                        RecipeService.CraftingPlan plan = planOpt.get();
                        String stepsStr = plan.craftingSteps().stream()
                                .map(s -> s.ingredientToCraft().toString())
                                .collect(Collectors.joining(" -> "));
                        logger.info("Finalized Crafting Plan: Raw Materials: " + plan.rawIngredients() + " | Steps: " + stepsStr);
                        agent.getBlackboard().put(BlackboardKeys.CRAFTING_PLAN, plan);
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

    private int calculateNeededForStep(RecipeService.CraftingStep step, RecipeService.CraftingPlan plan, CraftingContext context,
            Map<Ingredient, RecipeService.CraftingPath> resolvedPlan) {
        Ingredient ingredient = step.ingredientToCraft();
        int totalDemand = calculateFullDemand(ingredient, plan, resolvedPlan);
        int ownedAmount = context.getAvailableCount(ingredient);
        return Math.max(0, totalDemand - ownedAmount);
    }

    private int calculateFullDemand(Ingredient target, RecipeService.CraftingPlan plan,
            Map<Ingredient, RecipeService.CraftingPath> resolvedPlan) {
        if (target.materials().contains(this.targetMaterial)) {
            return this.params.quantity();
        }
        int total = 0;
        for (RecipeService.CraftingStep step : plan.craftingSteps()) {
            RecipeService.CraftingPath chosenPath = resolvedPlan.get(step.ingredientToCraft());
            if (chosenPath == null) {
                continue; // This step isn't part of the resolved plan.
            }
            if (chosenPath.requirements().containsKey(target)) {
                int parentDemand = calculateFullDemand(step.ingredientToCraft(), plan, resolvedPlan);
                if (parentDemand > 0) {
                    int craftsToPerform = (int) Math.ceil((double) parentDemand / chosenPath.yield());
                    total += craftsToPerform * chosenPath.requirements().get(target);
                }
            }
        }
        return total > 0 ? total : 1; // Assume at least 1 is needed if it's an intermediate
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
                RecipeService.CraftingPlan plan = agent.getBlackboard().get(BlackboardKeys.CRAFTING_PLAN, RecipeService.CraftingPlan.class)
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
                            logger.info("CraftingContext created. Transitioning to PREPARING_CRAFT.");
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