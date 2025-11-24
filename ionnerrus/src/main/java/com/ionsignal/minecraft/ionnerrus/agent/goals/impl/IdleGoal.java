package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.PerformIdleTask;

/**
 * A transient goal representing a specific period of "Living Statue" behavior.
 */
public class IdleGoal implements Goal {
    private static final int IDLE_CYCLE_TICKS = 20 * 30; // when things are safe, slow update
    private final Object contextToken = new Object();
    private boolean hasRunTask = false;
    private boolean finished = false;

    public IdleGoal() {
        // no-op
    }

    @Override
    public void start(NerrusAgent agent) {
        // Reset orientation to a neutral state when entering idle
        if (agent.getPersona().isSpawned()) {
            agent.getPersona().getPhysicalBody().orientation().clearLookTarget();
        }
    }

    @Override
    public void process(NerrusAgent agent) {
        // If we haven't dispatched the task yet, do so now.
        if (!hasRunTask) {
            // Delegate the actual "looking around" logic to a Task.
            // This ensures the NerrusAgent loop receives a TaskCompleted event when done.
            agent.setCurrentTask(new PerformIdleTask(IDLE_CYCLE_TICKS));
            hasRunTask = true;
        } else {
            // If we are back in process() and hasRunTask is true, it means the task
            // has completed successfully (via NerrusAgent.handleTaskCompleted).
            // We can now mark this goal as finished to allow Autonomy to run again.
            this.finished = true;
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(NerrusAgent agent) {
        // Clean up physical state when leaving idle (e.g. interrupted by a user command)
        if (agent.getPersona().isSpawned()) {
            agent.getPersona().getPhysicalBody().orientation().clearLookTarget();
        }
    }

    @Override
    public GoalResult getFinalResult() {
        return new GoalResult.Success("Idle cycle completed.");
    }

    @Override
    public Object getContextToken() {
        return contextToken;
    }
}