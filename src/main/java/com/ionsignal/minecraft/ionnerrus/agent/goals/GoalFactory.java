package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GetBlockGoal;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;
import org.bukkit.Material;

import java.util.Map;
import java.util.Set;

public class GoalFactory {

    private final GoalRegistry goalRegistry;
    private final TaskFactory taskFactory;
    private final BlockTagManager blockTagManager;

    public GoalFactory(GoalRegistry goalRegistry, TaskFactory taskFactory, BlockTagManager blockTagManager) {
        this.goalRegistry = goalRegistry;
        this.taskFactory = taskFactory;
        this.blockTagManager = blockTagManager;
    }

    @SuppressWarnings("unchecked")
    public Goal createGoal(String name, Map<String, Object> params) {
        GoalDefinition definition = goalRegistry.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown goal: " + name);
        }

        // Validate parameters
        for (Map.Entry<String, ParameterDefinition> entry : definition.parameters().entrySet()) {
            String paramName = entry.getKey();
            ParameterDefinition paramDef = entry.getValue();
            if (paramDef.required() && !params.containsKey(paramName)) {
                throw new IllegalArgumentException("Missing required parameter '" + paramName + "' for goal '" + name + "'");
            }
        }

        switch (name.toUpperCase()) {
            case "GET_BLOCKS":
                try {
                    String groupName = (String) params.get("groupName");
                    int quantity = ((Number) params.get("quantity")).intValue();

                    Set<Material> materials = blockTagManager.getMaterialSet(groupName);
                    if (materials == null) {
                        throw new IllegalArgumentException("Unknown block group: " + groupName);
                    }

                    return new GetBlockGoal(taskFactory, materials, groupName, quantity);
                } catch (ClassCastException | NullPointerException e) {
                    throw new IllegalArgumentException("Invalid parameter types for goal '" + name + "'", e);
                }

                // Future goals like "CRAFT_ITEM" would go here
                // case "CRAFT_ITEM":
                //     ...

            default:
                throw new IllegalArgumentException("Goal '" + name + "' is defined but not implemented in GoalFactory.");
        }
    }
}