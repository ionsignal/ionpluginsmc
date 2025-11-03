package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.MineOreParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;

/**
 * A "Guardrail" Goal that exists to capture the user's intent to mine for specific ores,
 * but immediately fails with a message explaining the agent's limitations. This prevents
 * the LLM from misusing other tools for a task it cannot perform.
 */
public class MineOreGoal implements Goal {
    private boolean finished = false;
    private GoalResult finalResult;

    @Override
    public void start(NerrusAgent agent) {
        // This goal provides immediate feedback to the LLM.
        this.finalResult = new GoalResult.Failure(
                "The objective failed because I cannot mine for specific ores. I can only gather common, surface-level blocks using the GET_BLOCKS tool.");
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

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "MINE_ORE",
                    "Searches for and mines a specific quantity of valuable ores like iron, coal, or diamonds underground.",
                    MineOreParameters.class);
        }
    }
}