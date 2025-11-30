package com.ionsignal.minecraft.ionnerrus.agent.goals.impl.stubbed;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A "Guardrail" Goal that exists to capture the user's intent to dig,
 * but immediately fails with a message explaining the agent's limitations.
 */
public class DigGoal implements Goal {
    private boolean finished = false;
    private GoalResult finalResult;

    @Override
    public void start(NerrusAgent agent, ExecutionToken token) {
        // This goal provides immediate feedback to the LLM.
        this.finalResult = new GoalResult.Failure(
                "The objective failed because I have a limitation that prevents me from digging deep underground.");
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

    public record DigParameters(
            @JsonProperty(required = true) @JsonPropertyDescription("The number of blocks to dig downwards.") int depth) {
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "DIG",
                    "Digs a hole straight down. Use this for requests involving digging or creating tunnels underground.",
                    DigParameters.class);
        }
    }
}