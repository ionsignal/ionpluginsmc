package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import org.bukkit.Material;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A skill for an agent to request items from a player and wait for delivery.
 * It periodically checks its inventory until the required amount is met or a timeout occurs.
 */
public class RequestItemSkill implements Skill<Boolean> {
    private final Material material;
    private final int requiredAmount;
    private final long timeoutSeconds;

    public RequestItemSkill(Material material, int requiredAmount) {
        this(material, requiredAmount, 60L); // Default timeout of 60 seconds
    }

    public RequestItemSkill(Material material, int requiredAmount, long timeoutSeconds) {
        this.material = material;
        this.requiredAmount = requiredAmount;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent, ExecutionToken token) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        // Immediately check how many we have to start with.
        new CountItemsSkill(Set.of(material)).execute(agent, token).thenAccept(initialCounts -> {
            int initialAmount = initialCounts.getOrDefault(material, 0);
            int amountNeeded = requiredAmount - initialAmount;
            if (amountNeeded <= 0) {
                future.complete(true);
                return;
            }
            agent.speak("Creator, I require " + amountNeeded + " " + material.name().replace('_', ' ').toLowerCase()
                    + " to complete my directive.");
            final BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    if (future.isDone()) {
                        this.cancel();
                        return;
                    }
                    new CountItemsSkill(Set.of(material)).execute(agent, token).thenAccept(currentCounts -> {
                        int currentAmount = currentCounts.getOrDefault(material, 0);
                        if (currentAmount >= requiredAmount) {
                            future.complete(true);
                            this.cancel();
                        }
                    });
                }
            }.runTaskTimer(IonNerrus.getInstance(), 20L, 20L); // Check every second
            future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete((result, ex) -> {
                task.cancel();
                if (ex != null) {
                    agent.speak("I will continue waiting for the items I requested.");
                    // The future is already completed exceptionally, no need to complete it again.
                }
            });
        });
        return future;
    }
}