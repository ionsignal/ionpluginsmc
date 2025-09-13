package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;

/**
 * A "Guardrail" Goal that exists to capture the user's intent to craft items,
 * but immediately fails with a message explaining the agent's limitations. This prevents
 * the LLM from getting stuck on an impossible task.
 */
public class CraftItemGoal implements Goal {
    private boolean finished = false;
    private GoalResult finalResult;

    @Override
    public void start(NerrusAgent agent) {
        // This goal provides immediate feedback to the LLM.
        this.finalResult = new GoalResult(GoalResult.Status.FAILURE,
                "The objective is impossible because I have a limitation that prevents me from crafting any items.");
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
                    "CRAFT_ITEM",
                    "Crafts items from materials using a crafting table or the inventory crafting grid.",
                    CraftItemParameters.class);
        }
    }
}