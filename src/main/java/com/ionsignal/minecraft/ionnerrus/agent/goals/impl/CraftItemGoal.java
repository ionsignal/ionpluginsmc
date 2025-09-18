package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.Ingredient;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService.CraftingPath;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CraftInInventorySkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindCollectableBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.PlaceBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.FindCollectableBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CraftItemGoal implements Goal {

    private enum State {
        PLANNING, RESOLVING_AND_ACQUIRING, WAITING_FOR_ACQUISITION, // New state to wait for the acquisition task
        EXECUTING_CRAFTING_STEPS,
        // ENSURING_CRAFTING_STATION,
        // PERFORMING_TABLE_CRAFT,
        COMPLETED, FAILED
    }

    private final CraftItemParameters params;
    private final RecipeService recipeService;
    private final TaskFactory taskFactory;
    private final Logger logger;

    private volatile State state = State.PLANNING;
    private GoalResult finalResult;
    private RecipeService.CraftingPlan craftingPlan;
    private Queue<RecipeService.CraftingStep> craftingStepsQueue;
    private Material targetMaterial;

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
        // CompletableFuture.runAsync(() -> {
        // Optional<RecipeService.CraftingPlan> planOpt = recipeService.createCraftingPlan(targetMaterial,
        // params.quantity());
        // if (planOpt.isEmpty()) {
        // // This is a more robust failure check.
        // // We can't use fail() here as we're on an async thread.
        // // The whenComplete block will handle the state transition.
        // return;
        // }
        // this.craftingPlan = planOpt.get();
        // this.craftingStepsQueue = new LinkedList<>(this.craftingPlan.craftingSteps());
        // logger.info("Finalized Crafting Plan for " + params.quantity() + " " + params.itemName() + ":");
        // logger.info(" -> Raw Materials: " + craftingPlan.rawIngredients());
        // logger.info(" -> Crafting Steps (in execution order): "
        // + craftingPlan.craftingSteps().stream().map(s ->
        // s.ingredientToCraft().toString()).collect(Collectors.joining(" -> ")));
        // // Deailed logging for debugging the new structure
        // craftingPlan.craftingSteps().forEach(step -> {
        // logger.info(" - Step: Craft " + step.ingredientToCraft());
        // step.paths().forEach(path -> {
        // logger.info(" - Path Option: Requires " + path.requirements());
        // });
        // });
        // }, IonNerrus.getInstance().getOffloadThreadExecutor()).whenCompleteAsync((v, ex) -> {
        // // This block is modified to kick off the new AcquireMaterialsTask for testing.
        // if (ex != null) {
        // fail("An error occurred during planning: " + ex.getMessage());
        // return;
        // }
        // if (this.craftingPlan == null) {
        // fail("I was unable to create a crafting plan for " + params.itemName() + ".");
        // return;
        // }
        // // Transition to the new state to begin acquisition.
        // // The process() method will pick this up on the next tick.
        // this.state = State.RESOLVING_AND_ACQUIRING;
        // logger.info("CraftItemGoal: Planning complete. Transitioning to RESOLVING_AND_ACQUIRING state.");
        // }, IonNerrus.getInstance().getMainThreadExecutor());
    }

    @Override
    public void process(NerrusAgent agent) {
        if (isFinished()) {
            return;
        }
        // Guard clause: If a task is running, wait for it to complete.
        if (agent.getCurrentTask() != null) {
            return;
        }
        Task nextTask = null;
        switch (state) {
            case PLANNING:
                // The first step is to create a task that performs the planning.
                nextTask = new Task() {
                    @Override
                    public CompletableFuture<Void> execute(NerrusAgent agent) {
                        return CompletableFuture.runAsync(() -> {
                            Optional<RecipeService.CraftingPlan> planOpt = recipeService.createCraftingPlan(targetMaterial,
                                    params.quantity());
                            if (planOpt.isEmpty()) {
                                fail("I was unable to create a crafting plan for " + params.itemName() + ".");
                                return;
                            }
                            craftingPlan = planOpt.get();
                            craftingStepsQueue = new LinkedList<>(craftingPlan.craftingSteps());
                            logger.info("Finalized Crafting Plan for " + params.quantity() + " " + params.itemName() + ":");
                            logger.info(" -> Raw Materials: " + craftingPlan.rawIngredients());
                            logger.info(" -> Crafting Steps (in execution order): "
                                    + craftingPlan.craftingSteps().stream().map(s -> s.ingredientToCraft().toString())
                                            .collect(Collectors.joining(" -> ")));
                            // On successful planning, transition the GOAL's state.
                            // The agent will call process() again after this task completes.
                            state = State.RESOLVING_AND_ACQUIRING;
                            logger.info("CraftItemGoal: Planning complete. Transitioning to RESOLVING_AND_ACQUIRING state.");
                        }, IonNerrus.getInstance().getOffloadThreadExecutor());
                    }

                    @Override
                    public void cancel() {
                    }
                };
                break;

            case RESOLVING_AND_ACQUIRING:
                logger.info("CraftItemGoal: State -> RESOLVING_AND_ACQUIRING");
                agent.speak("I need to gather some materials first.");
                // Create the new AcquireMaterialsTask with the full plan.
                nextTask = taskFactory.createTask("ACQUIRE_MATERIALS", Map.of(
                        "plan", this.craftingPlan,
                        "targetIngredient", Ingredient.of(this.targetMaterial),
                        "targetQuantity", params.quantity()));
                // After dispatching the task, transition to a state where we wait for its completion.
                this.state = State.WAITING_FOR_ACQUISITION;
                break;

            case WAITING_FOR_ACQUISITION:
                // This state is entered only after the AcquireMaterialsTask has finished (because currentTask is
                // null).
                // For now, we assume success and transition to the final state.
                logger.info("CraftItemGoal: Material acquisition task finished. Proceeding.");
                this.state = State.COMPLETED; // For testing, we stop here. Later this will go to EXECUTING_CRAFTING_STEPS.
                break;

            default:
                break;
        }
        if (nextTask != null) {
            agent.setCurrentTask(nextTask);
        } else if (state == State.COMPLETED) {
            // If we transitioned to COMPLETED and there's no next task, succeed the goal.
            succeed("TEST: Material acquisition task has completed. Goal is finished for now.");
        }
    }

    // private CompletableFuture<Optional<Location>> findOrPlaceTable(NerrusAgent agent) {
    // // 1. Find an existing table nearby
    // return new FindCollectableBlockSkill(Set.of(Material.CRAFTING_TABLE), 20, new
    // HashSet<>()).execute(agent)
    // .thenCompose(findResult -> {
    // if (findResult.status() == FindCollectableBlockResult.Status.SUCCESS) {
    // // Found a table, return its location.
    // return CompletableFuture.completedFuture(Optional.of(findResult.target().get().blockLocation()));
    // }
    // // 2. If not found, place one from inventory.
    // agent.speak("I'll place down a crafting table.");
    // return new PlaceBlockSkill(Material.CRAFTING_TABLE).execute(agent);
    // });
    // }

    private Task createSkillTask(CompletableFuture<?> future) {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                return future.thenApply(v -> null);
            }

            @Override
            public void cancel() {
                future.cancel(true);
            }
        };
    }

    private void succeed(String message) {
        this.finalResult = new GoalResult(GoalResult.Status.SUCCESS, message);
        this.state = State.COMPLETED;
    }

    private void fail(String message) {
        if (isFinished())
            return; // Prevent multiple failure messages
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