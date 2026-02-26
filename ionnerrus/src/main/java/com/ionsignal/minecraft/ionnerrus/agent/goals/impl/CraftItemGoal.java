package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.Ingredient;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalPrerequisite;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.helpers.CraftingContext;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tools.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.AcquireMaterialsTask;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.CraftExecutionTask;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.FindNearbyBlockTask;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.PlaceBlockTask;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.CraftingRecipe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class CraftItemGoal implements Goal {
    private enum State {
        PLANNING, // Creating the crafting blueprint
        ACQUIRING_MATERIALS, // Collecting raw materials or requesting sub-goals
        CREATING_CONTEXT, // Building virtual inventory snapshot
        FINDING_TABLE, // Searching world for crafting table (was ENSURING_STATION step 0-1)
        COUNTING_TABLE_INVENTORY, // Checking if agent has table in inventory (was ENSURING_STATION step 2-3)
        PLACING_TABLE, // Placing table from inventory (was ENSURING_STATION step 4)
        CRAFTING_TABLE_PREREQUISITE, // Waiting for sub-goal to craft a table (was ENSURING_STATION step 3)
        PREPARING_CRAFT, // Deciding which recipe to execute next
        EXECUTING_CRAFT, // Performing the actual craft operation
        COMPLETED, FAILED
    }

    /**
     * Message sent by the planning task after analyzing crafting requirements.
     */
    public static record PlanningResult(Status status, Optional<RecipeService.CraftingBlueprint> plan, String reason) {
        public enum Status {
            SUCCESS, FAILED
        }

        public static PlanningResult success(RecipeService.CraftingBlueprint plan) {
            return new PlanningResult(Status.SUCCESS, Optional.of(plan), "");
        }

        public static PlanningResult failure(String reason) {
            return new PlanningResult(Status.FAILED, Optional.empty(), reason);
        }
    }

    /**
     * Message sent by the acquisition task with material status.
     */
    public static record AcquisitionResult(
            Status status,
            Optional<GoalPrerequisite> prerequisite,
            Optional<Map<Ingredient, RecipeService.CraftingPath>> executionPlan) {
        public enum Status {
            SUCCESS, NEEDS_PREREQUISITE, FAILED
        }

        public static AcquisitionResult success(Map<Ingredient, RecipeService.CraftingPath> executionPlan) {
            return new AcquisitionResult(Status.SUCCESS, Optional.empty(), Optional.of(executionPlan));
        }

        public static AcquisitionResult prerequisite(GoalPrerequisite prereq) {
            return new AcquisitionResult(Status.NEEDS_PREREQUISITE, Optional.of(prereq), Optional.empty());
        }

        public static AcquisitionResult failure() {
            return new AcquisitionResult(Status.FAILED, Optional.empty(), Optional.empty());
        }
    }

    /**
     * Message sent after creating the crafting context with initial inventory snapshot.
     */
    public static record ContextCreationResult(CraftingContext context, int initialTargetItemCount) {
    }

    /**
     * Message sent after searching for a crafting table in the world.
     */
    public static record TableSearchResult(Status status, Optional<Location> location) {
        public enum Status {
            FOUND, NOT_FOUND
        }

        public static TableSearchResult found(Location location) {
            return new TableSearchResult(Status.FOUND, Optional.of(location));
        }

        public static TableSearchResult notFound() {
            return new TableSearchResult(Status.NOT_FOUND, Optional.empty());
        }
    }

    /**
     * Message sent after counting items in inventory.
     */
    public static record InventoryCountResult(Material material, int count) {
    }

    /**
     * Message sent after attempting to place a block.
     */
    public static record BlockPlacementResult(Status status, Optional<Location> location) {
        public enum Status {
            SUCCESS, FAILED
        }

        public static BlockPlacementResult success(Location location) {
            return new BlockPlacementResult(Status.SUCCESS, Optional.of(location));
        }

        public static BlockPlacementResult failure() {
            return new BlockPlacementResult(Status.FAILED, Optional.empty());
        }
    }

    /**
     * Message sent after completing a craft execution sequence.
     */
    public static record CraftExecutionResult(Status status) {
        public enum Status {
            SUCCESS, FAILED
        }
    }

    private final CraftItemParameters params;
    private final RecipeService recipeService;
    private final BlockTagManager blockTagManager;
    private final Logger logger;

    private boolean isFinished = false;
    private int initialTargetItemCount = 0;
    private State state = State.PLANNING;
    private GoalResult finalResult;
    private Material targetItemMaterial;

    private RecipeService.CraftingBlueprint plan;
    private CraftingContext context;
    private Map<Ingredient, RecipeService.CraftingPath> executionPlan;
    private Location craftingTableLocation;
    private CraftingRecipe recipeToExecute;
    private int craftTimesToExecute;

    public CraftItemGoal(CraftItemParameters params, RecipeService recipeService, BlockTagManager blockTagManager) {
        this.params = params;
        this.recipeService = recipeService;
        this.blockTagManager = blockTagManager;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public void start(NerrusAgent agent, ExecutionToken token) {
        try {
            this.targetItemMaterial = Material.valueOf(params.itemName().toUpperCase());
        } catch (IllegalArgumentException e) {
            fail("I do not know how to craft an item named '" + params.itemName() + "'.");
            return;
        }
        agent.speak("Let me see... to craft " + params.quantity() + " " + params.itemName() + ", I'll need a plan.");
        process(agent, token);
    }

    @Override
    public void resume(NerrusAgent agent, GoalResult subGoalResult, ExecutionToken token) {
        if (subGoalResult instanceof GoalResult.Failure failure) {
            fail("I couldn't get what I needed. " + failure.message());
            return;
        }
        this.isFinished = false;
        this.finalResult = null;
        // Determine where to resume based on what sub-goal just completed
        if (this.state == State.CRAFTING_TABLE_PREREQUISITE) {
            // Just crafted a crafting table, go back to checking for it
            agent.speak("Okay, I've got a crafting table now. Let me continue.");
            this.state = State.FINDING_TABLE;
        } else {
            // Came back from material acquisition, force context recreation to clear stale caches
            agent.speak("Alright, I've got new materials. Let me reassess.");
            this.state = State.CREATING_CONTEXT;
        }
        process(agent, token);
    }

    @Override
    public void onMessage(NerrusAgent agent, Object message, ExecutionToken token) {
        switch (message) {
            case PlanningResult result -> handlePlanningResult(agent, result);
            case AcquisitionResult result -> handleAcquisitionResult(agent, result);
            case ContextCreationResult result -> handleContextCreation(agent, result);
            case TableSearchResult result -> handleTableSearchResult(agent, result);
            case InventoryCountResult result -> handleInventoryCountResult(agent, result);
            case BlockPlacementResult result -> handleBlockPlacementResult(agent, result);
            case CraftExecutionResult result -> handleCraftExecutionResult(agent, result);
            default -> logger.warning("CraftItemGoal received unknown message type: " + message.getClass().getSimpleName());
        }
    }

    private void handlePlanningResult(NerrusAgent agent, PlanningResult result) {
        if (result.status() == PlanningResult.Status.FAILED) {
            fail(result.reason());
            return;
        }
        this.plan = result.plan().get(); // Store in instance field instead of blackboard
        this.state = State.ACQUIRING_MATERIALS;
        logger.info("Planning completed. Transitioning to material acquisition.");
    }

    private void handleAcquisitionResult(NerrusAgent agent, AcquisitionResult result) {
        switch (result.status()) {
            case SUCCESS -> {
                this.executionPlan = result.executionPlan().get(); // Store in instance field
                this.state = State.CREATING_CONTEXT;
                logger.info("Materials acquired. Creating crafting context.");
            }
            case NEEDS_PREREQUISITE -> {
                GoalPrerequisite prereq = result.prerequisite().get();
                declarePrerequisite("I need to acquire: " + prereq.goalName(), prereq);
            }
            case FAILED -> {
                fail("Failed to acquire necessary materials.");
            }
        }
    }

    private void handleContextCreation(NerrusAgent agent, ContextCreationResult result) {
        this.context = result.context(); // Store in instance field
        this.initialTargetItemCount = result.initialTargetItemCount(); // Store in instance field
        this.state = State.PREPARING_CRAFT;
        logger.info("Context created. Initial target item count: " + initialTargetItemCount
                + ". Transitioning to PREPARING_CRAFT.");
    }

    private void handleTableSearchResult(NerrusAgent agent, TableSearchResult result) {
        if (result.status() == TableSearchResult.Status.FOUND) {
            this.craftingTableLocation = result.location().get(); // Store in instance field
            this.state = State.PREPARING_CRAFT;
            logger.info("Crafting table found at: " + craftingTableLocation.toVector());
        } else {
            this.state = State.COUNTING_TABLE_INVENTORY;
            logger.info("No crafting table found nearby. Checking inventory.");
        }
    }

    private void handleInventoryCountResult(NerrusAgent agent, InventoryCountResult result) {
        if (result.material() == Material.CRAFTING_TABLE) {
            if (result.count() > 0) {
                this.state = State.PLACING_TABLE;
                logger.info("Found crafting table in inventory. Attempting to place.");
            } else {
                this.state = State.CRAFTING_TABLE_PREREQUISITE;
                logger.info("No crafting table in inventory. Need to craft one.");
            }
        }
    }

    private void handleBlockPlacementResult(NerrusAgent agent, BlockPlacementResult result) {
        if (result.status() == BlockPlacementResult.Status.SUCCESS) {
            this.craftingTableLocation = result.location().get(); // Store in instance field
            this.state = State.PREPARING_CRAFT;
            logger.info("Successfully placed crafting table at: " + craftingTableLocation.toVector());
        } else {
            fail("I have a crafting table, but I couldn't place it.");
        }
    }

    private void handleCraftExecutionResult(NerrusAgent agent, CraftExecutionResult result) {
        if (result.status() == CraftExecutionResult.Status.FAILED) {
            fail("Failed to execute craft operation.");
            return;
        }
        // After successful craft, loop back to preparation to check if more crafts needed
        this.state = State.PREPARING_CRAFT;
        logger.info("Craft execution succeeded. Checking if more crafts needed.");
    }

    @Override
    public void process(NerrusAgent agent, ExecutionToken token) {
        if (isFinished() || agent.getCurrentTask() != null) {
            return;
        }
        if (state == State.COMPLETED) {
            agent.speak("I've successfully crafted " + params.quantity() + " " + params.itemName() + "!");
            logger.info("CraftItemGoal completed successfully.");
            return;
        }
        if (state == State.FAILED) {
            agent.speak("I couldn't complete the crafting task.");
            logger.warning("CraftItemGoal failed.");
            return;
        }
        switch (state) {
            case PLANNING -> agent.setCurrentTask(createPlanningTask());
            case ACQUIRING_MATERIALS -> agent.setCurrentTask(createAcquisitionTask());
            case CREATING_CONTEXT -> agent.setCurrentTask(createContextCreationTask(token));
            case FINDING_TABLE -> agent.setCurrentTask(createFindTableTask(token));
            case COUNTING_TABLE_INVENTORY -> agent.setCurrentTask(createCountTableTask(token));
            case PLACING_TABLE -> agent.setCurrentTask(createPlaceTableTask());
            case CRAFTING_TABLE_PREREQUISITE -> handleTablePrerequisite(agent);
            case PREPARING_CRAFT -> handleCraftPreparation(agent);
            case EXECUTING_CRAFT -> agent.setCurrentTask(createCraftExecutionTask(token));
            default -> logger.warning("Unhandled state in process(): " + state);
        }
    }

    private void handleTablePrerequisite(NerrusAgent agent) {
        // Declare a sub-goal to craft a crafting table
        GoalPrerequisite prereq = new GoalPrerequisite(
                "CRAFT_ITEM",
                new CraftItemParameters("CRAFTING_TABLE", 1));
        declarePrerequisite("I need to craft a crafting table first.", prereq);
    }

    private void handleCraftPreparation(NerrusAgent agent) {
        if (context == null) {
            logger.severe("handleCraftPreparation called without context!");
            fail("Internal error: missing crafting context.");
            return;
        }
        // Create fresh caches for THIS preparation cycle only (fixes staleness bug)
        Map<Ingredient, Integer> neededCache = new HashMap<>();
        Map<Ingredient, Integer> demandCache = new HashMap<>();
        // Check if we have enough of the final product
        if (context.getAvailableCount(Ingredient.of(targetItemMaterial)) >= initialTargetItemCount + params.quantity()) {
            succeed("I have successfully crafted " + params.quantity() + " " + params.itemName() + ".");
            return;
        }
        // Find the next craftable step
        for (RecipeService.CraftingStep step : plan.craftingSteps()) {
            // Pass caches as parameters for thread-safety
            int needed = calculateNeeded(step.ingredientToCraft(), plan, context, executionPlan,
                    neededCache, demandCache);
            if (needed <= 0) {
                continue;
            }
            RecipeService.CraftingPath pathToCraft = executionPlan.get(step.ingredientToCraft());
            if (pathToCraft == null) {
                continue;
            }
            if (hasIngredientsForPath(pathToCraft, context)) {
                // Check if we need a crafting table for this recipe
                if (RecipeService.is3x3Recipe(pathToCraft.recipes().get(0))) {
                    if (craftingTableLocation == null) {
                        agent.speak("I'll need a crafting table for this.");
                        this.state = State.FINDING_TABLE;
                        logger.info("Transitioning to FINDING_TABLE for 3x3 recipe.");
                        return;
                    }
                }
                // Store what we're going to craft in instance fields
                int craftsToPerform = (int) Math.ceil((double) needed / pathToCraft.yield());
                this.recipeToExecute = pathToCraft.recipes().get(0);
                this.craftTimesToExecute = craftsToPerform;
                this.state = State.EXECUTING_CRAFT;
                logger.info("Preparation complete. Will craft " + craftsToPerform + "x "
                        + recipeToExecute.getResult().getType() + ". Transitioning to EXECUTING_CRAFT.");
                return;
            }
        }
        // No craftable steps found
        int currentCount = context.getAvailableCount(Ingredient.of(targetItemMaterial));
        int targetCount = initialTargetItemCount + params.quantity();
        if (currentCount >= targetCount) {
            succeed("I have successfully crafted " + params.quantity() + " " + params.itemName() + ".");
        } else {
            logger.info("No craftable steps available with current inventory. Re-evaluating material needs.");
            this.state = State.ACQUIRING_MATERIALS;
        }
    }

    private Task createPlanningTask() {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
                return CompletableFuture.runAsync(() -> {
                    // Post message instead of direct state mutation
                    Optional<RecipeService.CraftingBlueprint> planOpt = recipeService
                            .createCraftingBlueprint(targetItemMaterial, params.quantity());
                    IonNerrus.getInstance().getMainThreadExecutor().execute(() -> {
                        if (planOpt.isEmpty()) {
                            agent.postMessage(token,
                                    PlanningResult.failure("Unable to create crafting plan for " + params.itemName()));
                        } else {
                            agent.postMessage(token, PlanningResult.success(planOpt.get()));
                        }
                    });
                }, IonNerrus.getInstance().getOffloadThreadExecutor());
            }
        };
    }

    private Task createAcquisitionTask() {
        return new AcquireMaterialsTask(plan, Ingredient.of(targetItemMaterial), params.quantity(), blockTagManager);
    }

    private Task createContextCreationTask(ExecutionToken token) {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
                // Collect all materials that could possibly be involved
                Set<Material> allRelevantMaterials = new HashSet<>();
                plan.rawIngredients().keySet().forEach(ing -> allRelevantMaterials.addAll(ing.materials()));
                plan.craftingSteps().forEach(step -> {
                    allRelevantMaterials.addAll(step.ingredientToCraft().materials());
                    step.paths().forEach(path -> path.requirements().keySet().forEach(ing -> allRelevantMaterials.addAll(ing.materials())));
                });
                return new CountItemsSkill(allRelevantMaterials).execute(agent, token)
                        .thenAccept(inventoryCount -> {
                            CraftingContext ctx = new CraftingContext(inventoryCount);
                            int initialCount = ctx.getAvailableCount(Ingredient.of(targetItemMaterial));
                            // Post message instead of blackboard write
                            agent.postMessage(token, new ContextCreationResult(ctx, initialCount));
                        });
            }
        };
    }

    private Task createFindTableTask(ExecutionToken token) {
        return new FindNearbyBlockTask(Material.CRAFTING_TABLE, 20);
    }

    private Task createCountTableTask(ExecutionToken token) {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
                return new CountItemsSkill(Set.of(Material.CRAFTING_TABLE)).execute(agent, token)
                        .thenAccept(counts -> {
                            int count = counts.getOrDefault(Material.CRAFTING_TABLE, 0);
                            // Post message instead of blackboard write
                            agent.postMessage(token, new InventoryCountResult(Material.CRAFTING_TABLE, count));
                        });
            }
        };
    }

    private Task createPlaceTableTask() {
        return new PlaceBlockTask(Material.CRAFTING_TABLE);
    }

    private Task createCraftExecutionTask(ExecutionToken token) {
        if (context == null) {
            throw new IllegalStateException("Cannot execute craft without context");
        }
        if (recipeToExecute == null) {
            throw new IllegalStateException("handleCraftPreparation must be called before creating craft execution task");
        }
        return new CraftExecutionTask(recipeToExecute, craftTimesToExecute, context, Optional.ofNullable(this.craftingTableLocation));
    }

    private int calculateTotalDemand(
            Ingredient target,
            RecipeService.CraftingBlueprint plan,
            Map<Ingredient, RecipeService.CraftingPath> executionPlan,
            Map<Ingredient, Integer> demandCache) {
        if (demandCache.containsKey(target)) {
            return demandCache.get(target);
        }
        int totalDemand;
        if (target.materials().contains(this.targetItemMaterial)) {
            totalDemand = this.initialTargetItemCount + this.params.quantity();
        } else {
            totalDemand = 0;
            for (RecipeService.CraftingStep parentStep : plan.craftingSteps()) {
                RecipeService.CraftingPath chosenPath = executionPlan.get(parentStep.ingredientToCraft());
                if (chosenPath == null || !chosenPath.requirements().containsKey(target)) {
                    continue;
                }
                // RECURSIVE CALL: Pass demandCache parameter
                int parentTotalDemand = calculateTotalDemand(parentStep.ingredientToCraft(), plan, executionPlan, demandCache);
                if (parentTotalDemand > 0) {
                    int craftsToPerform = (int) Math.ceil((double) parentTotalDemand / chosenPath.yield());
                    totalDemand += craftsToPerform * chosenPath.requirements().get(target);
                }
            }
        }
        demandCache.put(target, totalDemand);
        return totalDemand;
    }

    // Updated signature to accept cache parameters
    private int calculateNeeded(
            Ingredient target,
            RecipeService.CraftingBlueprint plan,
            CraftingContext context,
            Map<Ingredient, RecipeService.CraftingPath> executionPlan,
            Map<Ingredient, Integer> neededCache,
            Map<Ingredient, Integer> demandCache) {
        if (neededCache.containsKey(target)) {
            return neededCache.get(target);
        }
        int totalDemand = calculateTotalDemand(target, plan, executionPlan, demandCache);
        int ownedAmount = context.getAvailableCount(target);
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
                    "Crafts items from materials. This is a high-level tool that will automatically gather required raw materials and"
                            + " perform all necessary intermediate crafting steps.",
                    CraftItemParameters.class);
        }
    }
}