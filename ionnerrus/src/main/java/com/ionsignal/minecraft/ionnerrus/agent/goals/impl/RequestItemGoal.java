package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.RequestItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.RequestItemSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Material;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

public class RequestItemGoal implements Goal {
    private final Object contextToken = new Object();
    private final RequestItemParameters params;
    private final Material materialToRequest;
    private boolean finished = false;
    private GoalResult finalResult;

    public RequestItemGoal(RequestItemParameters params, Material materialToRequest) {
        this.params = params;
        this.materialToRequest = materialToRequest;
    }

    @Override
    public void start(NerrusAgent agent) {
        // The skill itself handles the speaking.
    }

    @Override
    public void process(NerrusAgent agent) {
        if (isFinished() || agent.getCurrentTask() != null) {
            return;
        }

        Task requestTask = new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                return new RequestItemSkill(materialToRequest, params.quantity()).execute(agent)
                        .handle((success, ex) -> {
                            if (ex != null) {
                                if (ex.getCause() instanceof TimeoutException || ex instanceof TimeoutException) {
                                    finalResult = new GoalResult.Failure("The request for " + params.materialName() + " timed out.");
                                } else {
                                    finalResult = new GoalResult.Failure("An unexpected error occurred while requesting items.");
                                }
                            } else if (success) {
                                finalResult = new GoalResult.Success(
                                        "Successfully received " + params.quantity() + " " + params.materialName());
                            } else {
                                // This case should ideally not be hit with the current skill, but is a safeguard.
                                finalResult = new GoalResult.Failure("Failed to receive the requested items for an unknown reason.");
                            }
                            finished = true;
                            return null;
                        });
            }

            @Override
            public void cancel() {
                // The skill's future is handled by the chain.
            }
        };

        agent.setCurrentTask(requestTask);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(NerrusAgent agent) {
        if (!isFinished()) {
            this.finalResult = new GoalResult.Failure("Request item goal was cancelled.");
            this.finished = true;
        }
    }

    @Override
    public GoalResult getFinalResult() {
        return finalResult;
    }

    @Override
    public Object getContextToken() {
        return contextToken;
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "REQUEST_ITEM",
                    "Requests a specified quantity of an item from the player. Use this for materials that cannot be gathered from the surface (e.g., coal, iron_ore, diamonds) or crafted.",
                    RequestItemParameters.class);
        }
    }
}
