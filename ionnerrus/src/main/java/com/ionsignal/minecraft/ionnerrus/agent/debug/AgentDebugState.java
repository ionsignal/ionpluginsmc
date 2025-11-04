package com.ionsignal.minecraft.ionnerrus.agent.debug;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;

import org.bukkit.Location;

import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot of agent state for debugging.
 * Thread Safety: All fields are immutable or defensive copies.
 * 
 * Debug state record for agent message processing visualization.
 */
public record AgentDebugState(
        UUID agentId,
        String agentName,
        Location currentLocation,
        String currentGoalName, // Null if no goal
        String currentTaskName, // Null if no task
        Map<String, Object> blackboardSnapshot, // Defensive copy from Blackboard.getAllData()
        Path currentPath, // Null if not navigating
        String nextMessage // Class name of next message in queue
) {
    /**
     * Creates a snapshot from a live NerrusAgent.
     * MUST be called on the main thread to avoid race conditions.
     * 
     * This method is called from NerrusTick on the main server thread,
     * making all state access thread-safe.
     */
    public static AgentDebugState snapshot(NerrusAgent agent) {
        Goal currentGoal = agent.getCurrentGoal();
        Task currentTask = agent.getCurrentTask();
        // Use new getAllData() method for defensive copy of blackboard
        Map<String, Object> blackboardCopy;
        if (agent.getBlackboard() != null) {
            blackboardCopy = agent.getBlackboard().getAllData();
        } else {
            blackboardCopy = Map.of();
        }
        Path currentPath = null;
        if (agent.getPersona().isSpawned()) {
            currentPath = agent.getPersona().getNavigator().getCurrentPath();
        }
        return new AgentDebugState(
                agent.getPersona().getUniqueId(),
                agent.getName(),
                agent.getPersona().getLocation(),
                currentGoal != null ? currentGoal.getClass().getSimpleName() : null,
                currentTask != null ? currentTask.getClass().getSimpleName() : null,
                blackboardCopy,
                currentPath,
                agent.getNextMessageType() // New getter method for message type
        );
    }
}