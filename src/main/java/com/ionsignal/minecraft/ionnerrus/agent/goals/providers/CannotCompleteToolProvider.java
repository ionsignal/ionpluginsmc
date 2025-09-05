package com.ionsignal.minecraft.ionnerrus.agent.goals.providers;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CannotCompleteParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;

public class CannotCompleteToolProvider implements GoalProvider {
    @Override
    public ToolDefinition getToolDefinition(BlockTagManager blockTagManager, AgentService agentService) {
        return new ToolDefinition(
                "CANNOT_COMPLETE",
                "Call this function if and only if the user's objective is impossible to achieve with the other available tools. Use it to explain why the task cannot be done.",
                CannotCompleteParameters.class);
    }
}