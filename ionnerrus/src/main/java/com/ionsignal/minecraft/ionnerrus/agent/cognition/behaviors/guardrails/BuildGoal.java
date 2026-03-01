package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.guardrails;

import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.directors.tools.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.content.BlockTagManager;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A "Guardrail" Goal that exists to capture the user's intent to build structures,
 * but immediately fails with a message explaining the agent's limitations. This prevents
 * the LLM from misusing other tools for a task it cannot perform.
 */
public class BuildGoal implements Goal {
    private boolean finished = false;
    private GoalResult finalResult;

    @Override
    public void start(NerrusAgent agent, ExecutionToken token) {
        // This goal provides immediate feedback to the LLM.
        this.finalResult = new GoalResult.Failure(
                "The objective failed because my building capabilities are not yet enabled. I cannot construct complex structures.");
        this.finished = true;
    }

    @Override
    public void process(NerrusAgent agent, ExecutionToken token) {
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

    public record BuildParameters(
            @JsonProperty(required = true) @JsonPropertyDescription("The type of structure to build (e.g., 'house', 'wall', 'bridge').") String structureType,
            @JsonProperty() @JsonPropertyDescription("An optional detailed description of the structure to be built.") String description) {
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "BUILD",
                    "Builds structures like houses, walls, or bridges from blocks in the inventory.",
                    BuildParameters.class);
        }
    }
}