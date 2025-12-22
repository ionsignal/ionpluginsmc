package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken.Registration;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.WorkingMemory;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;

/**
 * A task that runs for a specific duration, actively updating the agent's orientation based on
 * sensory input every tick.
 */
public class PerformIdleTask implements Task {
    private final CompletableFuture<Void> future = new CompletableFuture<>();
    private final int durationTicks;

    private int ticksRun = 0;

    public PerformIdleTask(int durationTicks) {
        this.durationTicks = durationTicks;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
        if (this.future.isDone() || this.ticksRun > 0) {
            throw new IllegalStateException("PerformIdleTask cannot be reused. Please create a new instance.");
        }
        // Bind to token to prevent zombie tasks (Goal cancellation completes the future)
        Registration reg = token.onCancel(() -> {
            // Use localFuture instead of this.future
            if (!future.isDone()) {
                future.complete(null);
            }
        });
        this.future.whenComplete((v, ex) -> reg.close());
        return this.future;
    }

    @Override
    public void tick(NerrusAgent agent, ExecutionToken token) {
        // Safety check if agent/persona became invalid during the task
        if (!agent.getPersona().isSpawned()) {
            if (!this.future.isDone()) {
                this.future.complete(null);
            }
            return;
        }
        // Retrieve latest memory snapshot (updates happen in NerrusAgent.processMessages)
        WorkingMemory memory = agent.getSensorySystem().getWorkingMemory();
        if (memory.attentionTarget().isPresent()) {
            // If we see something interesting, track it.
            Location target = memory.attentionTarget().get().location();
            Location gazeTarget = target.clone().add(0, 1.6, 0);
            agent.getPersona().getPhysicalBody().orientation()
                    .face(gazeTarget, true, token);
        } else {
            // If nothing captures attention, return to neutral head pose.
            agent.getPersona().getPhysicalBody().orientation().clearLookTarget();
        }
        // Increment tick counter and check for completion
        this.ticksRun++;
        if (this.ticksRun >= this.durationTicks) {
            if (!this.future.isDone()) {
                this.future.complete(null);
            }
        }
    }
}