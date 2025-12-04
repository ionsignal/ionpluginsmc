package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.NavigateToLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class GoToLocationTask implements Task {
    private final Logger logger;
    private final Location targetLocation;

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
    public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
        // Calculate a gaze target at eye-level above the destination
        // This keeps the agent focused on where they are going, rather than staring blankly forward.
        Location gazeTarget = this.targetLocation.clone().add(0, 1.6, 0);
        // Use the skill with the explicit look target
        NavigateToLocationSkill navSkill = new NavigateToLocationSkill(this.targetLocation, gazeTarget);
        return navSkill.execute(agent, token).thenAccept(navResult -> {
            if (agent.getPersona().isSpawned() && agent.getPersona().getPersonaEntity() != null) {
                agent.getPersona().getPhysicalBody().orientation().clearLookTarget();
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
                default:
                    break;
            }
        });
    }
}