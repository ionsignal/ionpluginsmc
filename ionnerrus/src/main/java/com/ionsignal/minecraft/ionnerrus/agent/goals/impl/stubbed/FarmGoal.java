package com.ionsignal.minecraft.ionnerrus.agent.goals.impl.stubbed;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A "Guardrail" Goal that exists to capture the user's intent to farm,
 * but immediately fails with a message explaining the agent's limitations. This prevents
 * the LLM from misusing other tools for a task it cannot perform.
 */
public class FarmGoal implements Goal {
    private final Object contextToken = new Object();
    private boolean finished = false;
    private GoalResult finalResult;

    @Override
    public void start(NerrusAgent agent) {
        // This goal provides immediate feedback to the LLM.
        this.finalResult = new GoalResult.Failure(
                "The objective failed because I do not have the ability to farm. I cannot plant seeds, till soil, or harvest crops.");
        this.finished = true;
    }

    @Override
    public void process(NerrusAgent agent) {
        // No-op, the goal is finished immediately.
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(NerrusAgent agent) {
        // No-op
    }

    @Override
    public GoalResult getFinalResult() {
        return finalResult;
    }

    @Override
    public Object getContextToken() {
        return contextToken;
    }

    public record FarmParameters(
            @JsonProperty(required = true) @JsonPropertyDescription("The type of crop to farm (e.g., 'wheat', 'carrots', 'potatoes').") String cropType,
            @JsonProperty() @JsonPropertyDescription("The action to perform (e.g., 'plant', 'harvest', 'till').") String action) {
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "FARM",
                    "Manages a farm, which includes tilling soil, planting seeds, and harvesting crops like wheat or carrots.",
                    FarmParameters.class);
        }
    }
}