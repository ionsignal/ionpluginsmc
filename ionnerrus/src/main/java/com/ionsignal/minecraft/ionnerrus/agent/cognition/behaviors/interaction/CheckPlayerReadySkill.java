package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.interaction;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * A skill that monitors a target entity for social cues indicating they are
 * ready for an interaction. It completes when a "readiness score" threshold is
 * met or a timeout occurs.
 */
public class CheckPlayerReadySkill implements Skill<Boolean> {
    private final LivingEntity target;
    private final long timeoutSeconds;
    private final Logger logger;

    public CheckPlayerReadySkill(LivingEntity target, long timeoutSeconds) {
        this.target = target;
        this.timeoutSeconds = timeoutSeconds;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent, ExecutionToken token) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
        final BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (future.isDone()) {
                    this.cancel();
                    return;
                }
                // Check for timeout
                if (System.currentTimeMillis() - startTime.get() > timeoutSeconds * 1000) {
                    future.complete(false); // Timed out
                    this.cancel();
                    return;
                }
                // Check if target is still valid
                if (target == null || !target.isValid()) {
                    future.complete(false); // Target gone
                    this.cancel();
                    return;
                }
                double readinessScore = calculateReadinessScore(agent, target);
                // Threshold check
                if (readinessScore >= 2.5) {
                    future.complete(true); // Player is ready
                    this.cancel();
                }
            }
        }.runTaskTimer(IonNerrus.getInstance(), 0L, 20L); // Check every second
        // Ensure the task is cancelled if the future is completed externally
        future.whenComplete((res, err) -> task.cancel());
        return future;
    }

    private double calculateReadinessScore(NerrusAgent agent, LivingEntity target) {
        // Distance Score with a full score within 2 blocks, zero score beyond 4 (Max 1.0)
        double distance = target.getLocation().distance(agent.getPersona().getLocation());
        double distanceScore = Math.max(0, 1.0 - (Math.max(0, distance - 2.0) / 2.0));
        // Stillness Score (Max 1.0)
        double velocity = target.getVelocity().lengthSquared();
        double stillnessScore = (velocity < 0.08) ? 1.0 : 0.0;
        // Gaze Score (Max 1.0) - Only applicable to Players
        double dotProduct = 0.0;
        double gazeScore = 0.0;
        if (target instanceof Player player) {
            Vector playerLookDir = player.getEyeLocation().getDirection();
            Vector playerToPersonaDir = agent.getPersona().getLocation().add(0, 1.6, 0)
                    .toVector().subtract(player.getEyeLocation().toVector()).normalize();
            dotProduct = playerLookDir.dot(playerToPersonaDir);
            if (dotProduct > 0.9) {
                // A dot product of > 0.90 indicates the player is looking at the persona.
                gazeScore = 1.0;
            }
            logger.info("");
        } else {
            // For non-player entities we can't check gaze, so we give a default partial score if they are
            // close, assuming they are "aware".
            if (distance < 5.0) {
                gazeScore = 0.75;
            }
        }
        double totalScore = distanceScore + stillnessScore + gazeScore;
        logger.info(String.format(
                "Readiness score for %s: %.2f (Dist: %.2f, Still: %.2f, Gaze: %.2f | Raw Dist: %.2f, Vel: %.4f, Dot: %.2f)",
                target.getName(),
                totalScore,
                distanceScore,
                stillnessScore,
                gazeScore,
                distance,
                velocity,
                dotProduct));
        return totalScore;
    }
}