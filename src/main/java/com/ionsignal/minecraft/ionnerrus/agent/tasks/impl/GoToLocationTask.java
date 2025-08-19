package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.NavigateToLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class GoToLocationTask implements Task {
    private final Logger logger;
    private final String locationBlackboardKey;
    private volatile boolean cancelled = false;

    public GoToLocationTask(String locationBlackboardKey) {
        this.logger = IonNerrus.getInstance().getLogger();
        this.locationBlackboardKey = locationBlackboardKey;
    }

    public GoToLocationTask() {
        this(BlackboardKeys.TARGET_LOCATION);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        this.cancelled = false;
        Location target = agent.getBlackboard().getLocation(locationBlackboardKey).orElse(null);
        if (target == null) {
            agent.speak("I don't have a location to go to.");
            return CompletableFuture.completedFuture(null);
        }
        // Use the skill for simple movement, no look target.
        NavigateToLocationSkill navSkill = new NavigateToLocationSkill(target);
        return navSkill.execute(agent).thenAccept(success -> {
            if (cancelled)
                return;

            // A simple GoTo should always clean up the look state to prevent aimless staring.
            if (agent.getPersona().isSpawned() && agent.getPersona().getPersonaEntity() != null) {
                agent.getPersona().getPersonaEntity().getLookControl().stopLooking();
            }

            if (success) {
                logger.info("Agent has arrived.");
            } else {
                logger.info("Agent couldn't get to location: " + target.toString());
            }
        });
    }
}