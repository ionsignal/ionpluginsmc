package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core;

import com.ionsignal.minecraft.ionnerrus.agent.directors.tools.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.content.BlockTagManager;

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
     * @return The ToolDefinition for a specific goal.
     */
    ToolDefinition getToolDefinition(BlockTagManager blockTagManager);
}