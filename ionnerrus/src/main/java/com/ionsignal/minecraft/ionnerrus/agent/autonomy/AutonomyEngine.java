package com.ionsignal.minecraft.ionnerrus.agent.autonomy;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.IdleGoal;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.WorkingMemory;

import java.util.Optional;

/**
 * The stateless decision maker.
 * Evaluates sensory data to suggest high-level goals when the agent is idle.
 */
public class AutonomyEngine {

    @SuppressWarnings("unused")
    private final NerrusAgent agent;

    public AutonomyEngine(NerrusAgent agent) {
        this.agent = agent;
    }

    /**
     * Analyzes the current memory to determine if a new autonomous goal is warranted.
     *
     * @param memory
     *            The latest sensory snapshot.
     * @return An Optional containing a new Goal instance if action is required.
     */
    public Optional<Goal> suggestGoal(WorkingMemory memory) {
        // Basic implementation.
        // If the agent has no work, we default to the IdleGoal ("Living Statue").
        // Future expansions can implement "Boredom" or "Curiosity" logic here.
        return Optional.of(new IdleGoal());
    }
}