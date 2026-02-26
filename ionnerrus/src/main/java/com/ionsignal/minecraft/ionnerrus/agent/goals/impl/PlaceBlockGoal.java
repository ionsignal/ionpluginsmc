package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.PlaceBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tools.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.PlaceBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Material;

import java.util.concurrent.CompletableFuture;

public class PlaceBlockGoal implements Goal {
    private final PlaceBlockParameters params;
    private final Material materialToPlace;
    private boolean finished = false;
    private GoalResult finalResult;

    public PlaceBlockGoal(PlaceBlockParameters params, Material materialToPlace) {
        this.params = params;
        this.materialToPlace = materialToPlace;
    }

    @Override
    public void start(NerrusAgent agent, ExecutionToken token) {
        agent.speak("Okay, I'll place the " + params.materialName().toLowerCase().replace('_', ' ') + ".");
    }

    @Override
    public void process(NerrusAgent agent, ExecutionToken token) {
        if (isFinished() || agent.getCurrentTask() != null) {
            return;
        }

        Task placeTask = new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
                return new PlaceBlockSkill(materialToPlace).execute(agent, token)
                        .thenAccept(locationOpt -> {
                            if (locationOpt.isPresent()) {
                                finalResult = new GoalResult.Success(
                                        "Successfully placed " + materialToPlace.name() + " at " + locationOpt.get().toVector());
                            } else {
                                finalResult = new GoalResult.Failure(
                                        "Could not find a suitable place to place the " + materialToPlace.name()
                                                + ", or I do not have one in my inventory.");
                            }
                            finished = true;
                        });
            }
        };

        agent.setCurrentTask(placeTask);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(NerrusAgent agent) {
        if (!isFinished()) {
            this.finalResult = new GoalResult.Failure("Place block goal was cancelled.");
            this.finished = true;
        }
    }

    @Override
    public GoalResult getFinalResult() {
        return finalResult;
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "PLACE_BLOCK",
                    "Places a single block from the inventory into the world nearby. Used for placing items like crafting tables.",
                    PlaceBlockParameters.class);
        }
    }
}
