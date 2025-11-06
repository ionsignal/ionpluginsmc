package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GetBlockGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GiveItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.MineOreGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.PlaceBlockGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.RequestItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.BuildGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.CraftItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.DigGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.FarmGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.FollowPlayerGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GetBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GiveItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.PlaceBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.RequestItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FollowPlayerParameters;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;

import org.bukkit.Material;

import java.util.Set;

public class GoalFactory {
    private final TaskFactory taskFactory;
    private final BlockTagManager blockTagManager;
    private final RecipeService recipeService;

    public GoalFactory(TaskFactory taskFactory, BlockTagManager blockTagManager, RecipeService recipeService) {
        this.taskFactory = taskFactory;
        this.blockTagManager = blockTagManager;
        this.recipeService = recipeService;
    }

    public Goal createGoal(String name, Object parameters) {
        switch (name.toUpperCase()) {
            case "DIG":
                return new DigGoal();
            case "BUILD":
                return new BuildGoal();
            case "FARM":
                return new FarmGoal();
            case "MINE_ORE":
                return new MineOreGoal();
            case "GET_BLOCKS":
                GetBlockParameters getBlockParams = (GetBlockParameters) parameters;
                Set<Material> materials = blockTagManager.getMaterialSet(getBlockParams.groupName());
                if (materials == null) {
                    throw new IllegalArgumentException("Unknown block group: " + getBlockParams.groupName());
                }
                return new GetBlockGoal(materials, getBlockParams);
            case "GIVE_ITEM":
                GiveItemParameters giveItemParams = (GiveItemParameters) parameters;
                Material materialToGive;
                try {
                    materialToGive = Material.valueOf(giveItemParams.materialName().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown material name: " + giveItemParams.materialName());
                }
                return new GiveItemGoal(giveItemParams, materialToGive);
            case "CRAFT_ITEM":
                CraftItemParameters craftParams = (CraftItemParameters) parameters;
                return new CraftItemGoal(craftParams, recipeService, blockTagManager, taskFactory);
            case "FOLLOW_PLAYER":
                FollowPlayerParameters followParams = (FollowPlayerParameters) parameters;
                return new FollowPlayerGoal(followParams);
            case "PLACE_BLOCK":
                PlaceBlockParameters placeParams = (PlaceBlockParameters) parameters;
                Material materialToPlace;
                try {
                    materialToPlace = Material.valueOf(placeParams.materialName().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown material name for placement: " + placeParams.materialName());
                }
                return new PlaceBlockGoal(placeParams, materialToPlace);
            case "REQUEST_ITEM":
                RequestItemParameters requestParams = (RequestItemParameters) parameters;
                Material materialToRequest;
                try {
                    materialToRequest = Material.valueOf(requestParams.materialName().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown material name for request: " + requestParams.materialName());
                }
                return new RequestItemGoal(requestParams, materialToRequest);
            default:
                throw new IllegalArgumentException("Goal '" + name + "' is defined but not implemented in GoalFactory.");
        }
    }
}