package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;

/**
 * Defines the contract for classes that can provide a ToolDefinition for the LLM.
 * This allows for automatic discovery and registration of agent capabilities.
 */
@FunctionalInterface
public interface GoalProvider {
    /**
     * Gets the tool definition provided by this class.
     *
     * @param blockTagManager
     *            A manager for accessing block group information, useful for schema enhancement.
     * @param agentService
     *            The service for accessing agent information, useful for schema enhancement.
     * @return The ToolDefinition for a specific goal.
     */
    ToolDefinition getToolDefinition(BlockTagManager blockTagManager, AgentService agentService);
}