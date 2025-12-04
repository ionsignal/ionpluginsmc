package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.messages.common.MovementUpdate;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FollowEntitySkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * A continuous task that follows a specific entity handling tactical repathing logic internally.
 * The task completes only if the target is lost or a failure occurs.
 */
public class FollowEntityTask implements Task {
    private static final long REPATH_COOLDOWN_MS = 2000;

    private final Entity target;
    private final double followDistance;
    private final double stopDistance;

    private long lastRepathTime = 0;
    private CompletableFuture<Void> taskFuture;

    public FollowEntityTask(Entity target, double followDistance, double stopDistance) {
        this.target = target;
        this.followDistance = followDistance;
        this.stopDistance = stopDistance;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
        this.taskFuture = new CompletableFuture<>();
        startFollowing(agent, token);
        return taskFuture;
    }

    private void startFollowing(NerrusAgent agent, ExecutionToken token) {
        // Execute the skill, mapping the result to a MovementUpdate message given that the Agent will route
        // this message back to our onMessage() method.
        agent.executeSkill(new FollowEntitySkill(target, followDistance, stopDistance), token,
                res -> new MovementUpdate(res, null, null));
    }

    @Override
    public void onMessage(NerrusAgent agent, Object message, ExecutionToken token) {
        if (message instanceof MovementUpdate update) {
            // Handle tactical retries (Target moved, or path currently blocked)
            if (update.result() == MovementResult.REPATH_NEEDED ||
                    update.result() == MovementResult.UNREACHABLE ||
                    update.result() == MovementResult.STUCK) {
                if (target.isValid() && !target.isDead()) {
                    long now = System.currentTimeMillis();
                    long timeSinceLast = now - lastRepathTime;
                    if (timeSinceLast < REPATH_COOLDOWN_MS) {
                        // Throttle: If we just failed/repathed, wait before trying again.
                        // This prevents "machine gun" pathfinding when the target is unreachable (e.g. flying).
                        // This needs to be handle in AStartPathfinder where flying returns closest ground position
                        Bukkit.getScheduler().runTaskLater(IonNerrus.getInstance(), () -> {
                            // Check token before restarting to prevent zombie logic after cancellation
                            if (token.isActive() && !taskFuture.isDone()) {
                                startFollowing(agent, token);
                            }
                        }, 20L);
                    } else {
                        startFollowing(agent, token);
                    }
                    lastRepathTime = now;
                } else {
                    taskFuture.completeExceptionally(new IllegalStateException("Target lost during repath"));
                }
                return;
            }
            if (update.result() == MovementResult.CANCELLED) {
                taskFuture.completeExceptionally(new CancellationException("Follow operation cancelled"));
                return;
            }
            if (update.result() != MovementResult.SUCCESS) {
                taskFuture.completeExceptionally(new IllegalStateException("Follow failed: " + update.result()));
            }
        }
    }
}