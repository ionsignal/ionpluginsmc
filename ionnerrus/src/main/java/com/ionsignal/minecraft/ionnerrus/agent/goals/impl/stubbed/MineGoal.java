package com.ionsignal.minecraft.ionnerrus.agent.goals.impl.stubbed;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tools.ToolDefinition;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A "Guardrail" Goal that exists to capture the user's intent to mine for specific ores,
 * but immediately fails with a message explaining the agent's limitations. This prevents
 * the LLM from misusing other tools for a task it cannot perform.
 */
public class MineGoal implements Goal {
    private boolean finished = false;
    private GoalResult finalResult;

    @Override
    public void start(NerrusAgent agent, ExecutionToken token) {
        // This goal provides immediate feedback to the LLM.
        this.finalResult = new GoalResult.Failure(
                "The objective failed because I cannot mine for specific ores. I can only gather common, surface-level blocks using the GATHER_BLOCK tool.");
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

    public record MineOreParameters(
            @JsonProperty(required = true) @JsonPropertyDescription("The name of the ore to mine (e.g., 'iron', 'coal', 'diamond').") String oreName,
            @JsonProperty(required = true) @JsonPropertyDescription("The number of ore blocks to mine.") int quantity) {
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "MINE",
                    "Searches for and mines a specific quantity of valuable ores like iron, coal, or diamonds underground.",
                    MineOreParameters.class);
        }
    }
}