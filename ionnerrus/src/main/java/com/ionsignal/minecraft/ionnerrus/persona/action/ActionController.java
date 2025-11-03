package com.ionsignal.minecraft.ionnerrus.persona.action;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.ionsignal.minecraft.ionnerrus.persona.Persona;

public class ActionController {

    private final Persona persona;
    private final Queue<Action> actionQueue = new ConcurrentLinkedQueue<>();
    private Action currentAction;

    public ActionController(Persona persona) {
        this.persona = persona;
    }

    public void schedule(Action action) {
        actionQueue.add(action);
    }

    public void tick() {
        if (currentAction == null) {
            startNextAction();
        }

        if (currentAction != null) {
            currentAction.tick();
            if (currentAction.getStatus() != ActionStatus.RUNNING) {
                currentAction = null;
                startNextAction();
            }
        }
    }

    private void startNextAction() {
        if (currentAction == null && !actionQueue.isEmpty()) {
            currentAction = actionQueue.poll();
            currentAction.start(persona);
        }
    }

    public void clear() {
        if (currentAction != null) {
            currentAction.stop();
            currentAction = null;
        }
        actionQueue.clear();
    }
}