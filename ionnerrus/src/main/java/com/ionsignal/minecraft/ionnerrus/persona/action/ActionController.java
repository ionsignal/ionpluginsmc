package com.ionsignal.minecraft.ionnerrus.persona.action;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;

import org.jetbrains.annotations.Nullable;

public class ActionController {
    private final PersonaEntity personaEntity;
    private final Queue<Action> actionQueue = new ConcurrentLinkedQueue<>();

    private Action currentAction;

    @Nullable
    private ActionStatus lastCompletedStatus = null;

    public ActionController(PersonaEntity personaEntity) {
        this.personaEntity = personaEntity;
    }

    public void schedule(Action action) {
        actionQueue.add(action);
    }

    public void tick() {
        // Reset status from previous tick
        this.lastCompletedStatus = null;
        if (currentAction == null) {
            startNextAction();
        }
        if (currentAction != null) {
            currentAction.tick();
            ActionStatus status = currentAction.getStatus();
            if (status != ActionStatus.RUNNING) {
                // Capture the terminal status before nulling the action
                this.lastCompletedStatus = status;
                currentAction = null;
                startNextAction();
            }
        }
    }

    private void startNextAction() {
        if (currentAction == null && !actionQueue.isEmpty()) {
            currentAction = actionQueue.poll();
            currentAction.start(personaEntity);
        }
    }

    public void clear() {
        actionQueue.clear();
        if (currentAction != null) {
            currentAction.stop();
            currentAction = null;
            // Ensure the tick loop picks this up to complete the future
            lastCompletedStatus = ActionStatus.CANCELLED;
        }
    }

    /**
     * Gets the status of an action that completed during the most recent tick.
     * This is transient and only valid for one tick.
     */
    public Optional<ActionStatus> getLastCompletedStatus() {
        return Optional.ofNullable(lastCompletedStatus);
    }

    public boolean isBusy() {
        return currentAction != null;
    }
}