package com.ionsignal.minecraft.ionnerrus.agent.tasks;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.Ingredient;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.AcquireMaterialsTask;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.helpers.CraftingContext;

import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.CraftExecutionTask;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.FindNearbyBlockTask;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.FindBiomeTask;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.PlaceBlockTask;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.inventory.CraftingRecipe;

import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TaskFactory {
    private final Logger logger;
    private final BlockTagManager blockTagManager;

    public TaskFactory(BlockTagManager blockTagManager) {
        this.logger = IonNerrus.getInstance().getLogger();
        this.blockTagManager = blockTagManager;
    }

    @SuppressWarnings("unchecked")
    public Task createTask(String taskName, Map<String, Object> parameters) {
        try {
            switch (taskName.toUpperCase()) {
                // case "FIND_BIOME":
                // Set<Biome> biomes = (Set<Biome>) parameters.get("biomes");
                // int findRadius = (int) parameters.getOrDefault("radius", 1000);
                // return new FindBiomeTask(biomes, findRadius);

                // case "FIND_NEARBY_BLOCK":
                // Material materialToFind = (Material) parameters.get("material");
                // int searchRadius = (int) parameters.getOrDefault("radius", 20);
                // return new FindNearbyBlockTask(materialToFind, searchRadius);

                // case "PLACE_BLOCK":
                // Material materialToPlace = (Material) parameters.get("material");
                // return new PlaceBlockTask(materialToPlace);

                // case "EXECUTE_CRAFT":
                // CraftingRecipe recipeToExecute = (CraftingRecipe) parameters.get("recipe");
                // int times = (int) parameters.get("timesToCraft");
                // CraftingContext context = (CraftingContext) parameters.get("context");
                // return new CraftExecutionTask(recipeToExecute, times, context);

                // case "ACQUIRE_MATERIALS":
                // RecipeService.CraftingBlueprint plan = (RecipeService.CraftingBlueprint) parameters.get("plan");
                // Ingredient targetIngredient = (Ingredient) parameters.get("targetIngredient");
                // int quantity = (int) parameters.get("targetQuantity");
                // return new AcquireMaterialsTask(plan, targetIngredient, quantity, this.blockTagManager);

                default:
                    logger.warning("Unknown task name: " + taskName);
                    return null;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create task '" + taskName + "' with parameters: " + parameters, e);
            return null;
        }
    }
}