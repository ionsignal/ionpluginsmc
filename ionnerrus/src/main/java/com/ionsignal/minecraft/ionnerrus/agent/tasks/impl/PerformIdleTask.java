package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.WorkingMemory;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;

import org.bukkit.Location;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;

/**
 * A task that runs for a specific duration, actively updating the agent's
 * orientation based on sensory input every tick.
 */
public class PerformIdleTask implements Task {
    private final int durationTicks;
    private final CompletableFuture<Void> future = new CompletableFuture<>();

    private BukkitTask task;
    private int ticksRun = 0;

    public PerformIdleTask(int durationTicks) {
        this.durationTicks = durationTicks;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
        // Schedule a repeating task on the main thread to monitor sensory data
        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                // Safety check if agent/persona became invalid during the task
                if (!agent.getPersona().isSpawned()) {
                    cancel();
                    future.complete(null);
                    return;
                }
                // Retrieve latest memory snapshot (updates happen in NerrusAgent.processMessages)
                WorkingMemory memory = agent.getSensorySystem().getWorkingMemory();
                if (memory.attentionTarget().isPresent()) {
                    // If we see something interesting, track it.
                    // We use 'face' (fire-and-forget) with body turning enabled for a natural look.
                    Location target = memory.attentionTarget().get().location();
                    Location gazeTarget = target.clone().add(0, 1.6, 0);
                    agent.getPersona().getPhysicalBody().orientation()
                            .face(gazeTarget, true);
                } else {
                    // If nothing captures attention, return to neutral head pose.
                    // In the future, random procedural glancing could go here.
                    agent.getPersona().getPhysicalBody().orientation().clearLookTarget();
                }
                ticksRun++;
                if (ticksRun >= durationTicks) {
                    this.cancel();
                    future.complete(null);
                }
            }
        }.runTaskTimer(IonNerrus.getInstance(), 0L, 1L);
        return future;
    }
}