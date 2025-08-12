package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GetBlockGoal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GetBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;

import org.bukkit.Material;

import java.util.Set;

public class GoalFactory {
    private final TaskFactory taskFactory;
    private final BlockTagManager blockTagManager;

    public GoalFactory(TaskFactory taskFactory, BlockTagManager blockTagManager) {
        this.taskFactory = taskFactory;
        this.blockTagManager = blockTagManager;
    }

    public Goal createGoal(String name, Object parameters) {
        switch (name.toUpperCase()) {
            case "GET_BLOCKS":
                // The cast is safe because the ReActDirector used the correct class from the ToolDefinition.
                GetBlockParameters getBlockParams = (GetBlockParameters) parameters;
                Set<Material> materials = blockTagManager.getMaterialSet(getBlockParams.groupName());
                if (materials == null) {
                    // This validation is still necessary as the LLM might hallucinate a group name.
                    throw new IllegalArgumentException("Unknown block group: " + getBlockParams.groupName());
                }
                // The Goal's constructor now takes the typed parameter object directly.
                return new GetBlockGoal(taskFactory, materials, getBlockParams);
            // Future goals like "CRAFT_ITEM" would go here
            // case "CRAFT_ITEM":
            // ...
            default:
                throw new IllegalArgumentException("Goal '" + name + "' is defined but not implemented in GoalFactory.");
        }
    }
}