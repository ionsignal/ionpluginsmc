package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.helpers.CraftingContext;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.ExecuteCraftSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Location;
import org.bukkit.inventory.CraftingRecipe;

import java.util.concurrent.CompletableFuture;

/**
 * Executes a specific crafting recipe a given number of times, updating a
 * CraftingContext after each successful craft.
 */
public class CraftExecutionTask implements Task {
    private final CraftingRecipe recipe;
    private final int timesToCraft;
    private final CraftingContext context;
    private volatile boolean cancelled = false;

    public CraftExecutionTask(CraftingRecipe recipe, int timesToCraft, CraftingContext context) {
        this.recipe = recipe;
        this.timesToCraft = timesToCraft;
        this.context = context;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        return craftLoop(agent, timesToCraft);
    }

    private CompletableFuture<Void> craftLoop(NerrusAgent agent, int remaining) {
        if (remaining <= 0 || cancelled) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Boolean> craftFuture;
        boolean is3x3 = RecipeService.is3x3Recipe(recipe);
        // For 3x3 recipes, the parent Goal MUST have placed the table location on the blackboard.
        Location tableLocation = is3x3
                ? agent.getBlackboard().getLocation(BlackboardKeys.CRAFTING_TABLE_LOCATION)
                        .orElseThrow(() -> new IllegalStateException(
                                "CraftExecutionTask requires a crafting table location for 3x3 recipes."))
                : null;
        craftFuture = new ExecuteCraftSkill(recipe, tableLocation).execute(agent);
        return craftFuture.thenCompose(success -> {
            if (cancelled)
                return CompletableFuture.completedFuture(null);
            if (!success) {
                return CompletableFuture
                        .failedFuture(new RuntimeException("Failed to execute craft for " + recipe.getResult().getType()));
            }
            // On success, update the context and recurse.
            context.consumeIngredientsFor(recipe);
            context.addCraftedItem(recipe.getResult());
            return craftLoop(agent, remaining - 1);
        });
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }
}