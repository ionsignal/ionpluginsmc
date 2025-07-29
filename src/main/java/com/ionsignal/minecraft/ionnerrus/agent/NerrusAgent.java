package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.Bukkit;

import java.util.logging.Level;

public class NerrusAgent {
    private final Persona persona;
    private final IonNerrus plugin;
    private Task currentTask = null;
    private Goal currentGoal = null;
    private final Blackboard blackboard;

    public NerrusAgent(Persona persona, IonNerrus plugin) {
        this.persona = persona;
        this.plugin = plugin;
        this.blackboard = new Blackboard();
    }

    public Persona getPersona() {
        return persona;
    }

    public String getName() {
        return persona.getName();
    }

    public Blackboard getBlackboard() {
        return blackboard;
    }

    public void assignGoal(Goal goal) {
        if (this.currentGoal != null) {
            this.currentGoal.stop(this);
        }
        if (this.currentTask != null) {
            this.currentTask.cancel();
            this.currentTask = null;
        }

        this.blackboard.clear();
        this.currentGoal = goal;

        if (this.currentGoal != null) {
            this.currentGoal.start(this);
            processNextStep();
        }
    }

    public void setCurrentTask(Task task) {
        this.currentTask = task;
        if (this.currentTask != null) {
            this.currentTask.execute(this)
                    .whenCompleteAsync((v, ex) -> {
                        if (ex != null) {
                            plugin.getLogger().log(Level.SEVERE, "Task failed for agent " + getName(), ex.getCause());
                            speak("I ran into an unexpected problem with my task.");
                            // In a reactive system, we might want to put failure info on the blackboard
                            getBlackboard().put(BlackboardKeys.ISSUE, "TASK_EXCEPTION");
                        }
                        onTaskComplete();
                    }, runnable -> Bukkit.getScheduler().runTask(plugin, runnable));
        }
    }

    private void processNextStep() {
        if (currentGoal == null) {
            return;
        }
        currentGoal.process(this);
        if (currentTask != null) {
            return;
        }
        if (currentGoal.isFinished()) {
            assignGoal(null);
        }
    }

    private void onTaskComplete() {
        if (this.currentGoal != null) {
            this.currentTask = null;
            processNextStep();
        }
    }

    public void speak(String message) {
        persona.speak(message);
    }
}