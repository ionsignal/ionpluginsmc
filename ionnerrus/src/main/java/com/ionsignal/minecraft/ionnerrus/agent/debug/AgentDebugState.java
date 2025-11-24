package com.ionsignal.minecraft.ionnerrus.agent.debug;

import com.ionsignal.minecraft.ioncore.debug.DebugStateSnapshot;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;

import org.bukkit.Location;

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
        String currentGoalName,
        String currentTaskName,
        Path currentPath,
        String nextMessage,
        int goalMailboxSize) implements DebugStateSnapshot {

    /**
     * Implement DebugStateSnapshot marker interface
     */
    @Override
    public String getDebugLabel() {
        return "Agent Execution: " + agentName;
    }

    /**
     * Creates a snapshot from a live NerrusAgent and is called on the main thread to avoid race
     * conditions, making all state access thread-safe.
     */
    public static AgentDebugState snapshot(NerrusAgent agent) {
        Goal currentGoal = agent.getCurrentGoal();
        Task currentTask = agent.getCurrentTask();
        Path currentPath = null;
        if (agent.getPersona().isSpawned()) {
            // TODO: We need to handle this using the new system
            // currentPath = agent.getPersona().getNavigator().getCurrentPath();
        }
        // Get goal mailbox size for debugging message queue depth
        // Note: size() on ConcurrentLinkedQueue is O(n), acceptable for debug visualization
        int mailboxSize = agent.getGoalMailboxSize();
        return new AgentDebugState(
                agent.getPersona().getUniqueId(),
                agent.getName(),
                agent.getPersona().getLocation(),
                currentGoal != null ? currentGoal.getClass().getSimpleName() : null,
                currentTask != null ? currentTask.getClass().getSimpleName() : null,
                currentPath,
                agent.getNextMessageType(),
                mailboxSize);
    }
}