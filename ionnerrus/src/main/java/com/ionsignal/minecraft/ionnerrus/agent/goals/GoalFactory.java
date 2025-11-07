package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GatherBlockGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GiveItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.MineGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.PlaceBlockGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.RequestItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.BuildGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.CraftItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.DigGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.FarmGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.FollowPlayerGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GatherBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GiveItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.PlaceBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.RequestItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FollowPlayerParameters;

import org.bukkit.Material;

import java.util.Set;

public class GoalFactory {
    private final BlockTagManager blockTagManager;
    private final RecipeService recipeService;

    public GoalFactory(BlockTagManager blockTagManager, RecipeService recipeService) {
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
            case "MINE":
                return new MineGoal();
            case "GATHER":
                GatherBlockParameters getBlockParams = (GatherBlockParameters) parameters;
                Set<Material> materials = blockTagManager.getMaterialSet(getBlockParams.groupName());
                if (materials == null) {
                    throw new IllegalArgumentException("Unknown block group: " + getBlockParams.groupName());
                }
                return new GatherBlockGoal(materials, getBlockParams);
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
                return new CraftItemGoal(craftParams, recipeService, blockTagManager);
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