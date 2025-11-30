package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.CraftItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.CraftItemGoal.CraftExecutionResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.helpers.CraftingContext;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.ExecuteCraftSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Location;
import org.bukkit.inventory.CraftingRecipe;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Executes a specific crafting recipe a given number of times, updating a
 * CraftingContext after each successful craft.
 */
public class CraftExecutionTask implements Task {
    private final CraftingRecipe recipe;
    private final int timesToCraft;
    private final CraftingContext context;
    private final Optional<Location> craftingTableLocation;
    private volatile boolean cancelled = false;

    public CraftExecutionTask(
            CraftingRecipe recipe, int timesToCraft,
            CraftingContext context,
            Optional<Location> craftingTableLocation) {
        this.recipe = recipe;
        this.timesToCraft = timesToCraft;
        this.context = context;
        this.craftingTableLocation = craftingTableLocation;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
        return craftLoop(agent, token, timesToCraft);
    }

    private CompletableFuture<Void> craftLoop(NerrusAgent agent, ExecutionToken token, int remaining) {
        if (remaining <= 0 || cancelled) {
            agent.postMessage(token, new CraftItemGoal.CraftExecutionResult(CraftExecutionResult.Status.SUCCESS));
            return CompletableFuture.completedFuture(null);
        }
        boolean is3x3 = RecipeService.is3x3Recipe(recipe);
        // If this is a 3x3 recipe, we MUST have a location.
        if (is3x3 && craftingTableLocation.isEmpty()) {
            agent.postMessage(token, new CraftItemGoal.CraftExecutionResult(CraftExecutionResult.Status.FAILED));
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "CraftExecutionTask requires a crafting table location for 3x3 recipes, but none was provided."));
        }
        // Pass the location from the optional field to the skill.
        CompletableFuture<Boolean> craftFuture = new ExecuteCraftSkill(recipe, craftingTableLocation.orElse(null)).execute(agent, token);
        return craftFuture.thenCompose(success -> {
            if (cancelled)
                return CompletableFuture.completedFuture(null);
            if (!success) {
                agent.postMessage(token, new CraftItemGoal.CraftExecutionResult(CraftExecutionResult.Status.FAILED));
                return CompletableFuture
                        .failedFuture(new RuntimeException("Failed to execute craft for " + recipe.getResult().getType()));
            }
            // On success, update the context and recurse.
            context.consumeIngredientsFor(recipe);
            context.addCraftedItem(recipe.getResult());
            return craftLoop(agent, token, remaining - 1);
        });
    }
}