package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
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
    private final Location targetLocation;
    private volatile boolean cancelled = false;
    private NerrusAgent agent;

    /**
     * Creates a task to navigate the agent to a specific location.
     * 
     * @param targetLocation
     *            The location to navigate to
     */
    public GoToLocationTask(Location targetLocation) {
        this.logger = IonNerrus.getInstance().getLogger();
        this.targetLocation = targetLocation;
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
        // Use the skill for simple movement, no look target.
        NavigateToLocationSkill navSkill = new NavigateToLocationSkill(this.targetLocation);
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
                    logger.warning("Agent " + agent.getName() + " could not find a path to " + this.targetLocation.toVector());
                    agent.speak("I can't find a way there.");
                    break;
                case STUCK:
                    logger.warning("Agent " + agent.getName() + " got stuck on the way to " + this.targetLocation.toVector());
                    agent.speak("I seem to be stuck.");
                    break;
                case CANCELLED:
                    logger.info("Agent " + agent.getName() + "'s navigation was cancelled.");
                    break;
            }
        });
    }
}