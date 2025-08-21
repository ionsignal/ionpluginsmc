package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.NavigateToLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.EngageResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;
import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class GoToLocationTask implements Task {
    private final Logger logger;
    private final String locationBlackboardKey;
    private volatile boolean cancelled = false;
    private NerrusAgent agent;

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
        if (agent != null && agent.getPersona().isSpawned()) {
            agent.getPersona().getNavigator().cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
        }
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent) {
        this.agent = agent;
        this.cancelled = false;
        Location target = agent.getBlackboard().getLocation(locationBlackboardKey).orElse(null);
        if (target == null) {
            agent.speak("I don't have a location to go to.");
            return CompletableFuture.completedFuture(null);
        }
        // Use the skill for simple movement, no look target.
        NavigateToLocationSkill navSkill = new NavigateToLocationSkill(target);
        return navSkill.execute(agent).thenAccept(navResult -> {
            if (cancelled)
                return;
            if (agent.getPersona().isSpawned() && agent.getPersona().getPersonaEntity() != null) {
                agent.getPersona().getPersonaEntity().getLookControl().stopLooking();
            }
            switch (navResult) {
                case SUCCESS:
                    logger.info("Agent " + agent.getName() + " has arrived.");
                    break;
                case UNREACHABLE:
                    logger.warning("Agent " + agent.getName() + " could not find a path to " + target.toVector());
                    agent.speak("I can't find a way there.");
                    break;
                case STUCK:
                    logger.warning("Agent " + agent.getName() + " got stuck on the way to " + target.toVector());
                    agent.speak("I seem to be stuck.");
                    break;
                case CANCELLED:
                    logger.info("Agent " + agent.getName() + "'s navigation was cancelled.");
                    break;
            }
        });
    }
}