package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.guardrails;

import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.directors.tools.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.content.BlockTagManager;

/**
 * A "Guardrail" Goal that exists to capture the user's intent to craft items, but immediately fails
 * with a message explaining the agent's limitations. This prevents the LLM from misusing other
 * tools for a task it cannot perform.
 */
public class CraftItemGoal implements Goal {
    private boolean finished = false;
    private GoalResult finalResult;

    public CraftItemGoal(CraftItemParameters params) {
        // No-op
    }

    @Override
    public void start(NerrusAgent agent, ExecutionToken token) {
        this.finalResult = new GoalResult.Failure(
                "The objective failed because my crafting capabilities are currently offline. I cannot craft items.");
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

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "CRAFT_ITEM",
                    "Crafts items from materials. This is a high-level tool that will automatically gather required raw materials and perform all necessary intermediate crafting steps.",
                    CraftItemParameters.class);
        }
    }
}
