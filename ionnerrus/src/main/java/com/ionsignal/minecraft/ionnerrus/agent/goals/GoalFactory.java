package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GatherBlockGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GiveItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.PlaceBlockGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.stubbed.BuildGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.stubbed.DigGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.stubbed.FarmGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.stubbed.MineGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.stubbed.CraftItemGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GatherBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.PlaceBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FollowPlayerParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.FollowPlayerGoal;

import org.bukkit.Material;

import java.util.Set;

public class GoalFactory {
    private final BlockTagManager blockTagManager;

    public GoalFactory(BlockTagManager blockTagManager) {
        this.blockTagManager = blockTagManager;
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
            case "CRAFT_ITEM":
                CraftItemParameters craftParams = (CraftItemParameters) parameters;
                return new CraftItemGoal(craftParams);
            case "GATHER_BLOCK":
                GatherBlockParameters getBlockParams = (GatherBlockParameters) parameters;
                Set<Material> materials = blockTagManager.getMaterialSet(getBlockParams.groupName());
                if (materials == null) {
                    throw new IllegalArgumentException("Unknown block group: " + getBlockParams.groupName());
                }
                return new GatherBlockGoal(materials, getBlockParams);
            case "GIVE_ITEM":
                GiveItemGoal.GiveItemParameters giveItemParams = (GiveItemGoal.GiveItemParameters) parameters;
                Material materialToGive;
                try {
                    materialToGive = Material.valueOf(giveItemParams.materialName().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown material name: " + giveItemParams.materialName());
                }
                return new GiveItemGoal(giveItemParams, materialToGive);
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
            default:
                throw new IllegalArgumentException("Goal '" + name + "' is defined but not implemented in GoalFactory.");
        }
    }
}