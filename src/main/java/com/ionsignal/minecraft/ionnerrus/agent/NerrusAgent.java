package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class NerrusAgent {
    private final Persona persona;
    private final IonNerrus plugin;
    private final Blackboard blackboard;

    private Task currentTask = null;
    private Goal currentGoal = null;
    private boolean isBusyWithDirective = false;
    private CompletableFuture<GoalResult> currentGoalFuture;

    public NerrusAgent(Persona persona, IonNerrus plugin) {
        this.persona = persona;
        this.plugin = plugin;
        this.blackboard = new Blackboard();
    }

    public boolean isBusyWithDirective() {
        return isBusyWithDirective;
    }

    public void setBusyWithDirective(boolean isBusy) {
        this.isBusyWithDirective = isBusy;
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

    public Goal getCurrentGoal() {
        return currentGoal;
    }

    // MODIFIED: Method signature and logic changed to manage and return a CompletableFuture.
    public CompletableFuture<GoalResult> assignGoal(Goal goal) {
        if (this.currentGoal != null) {
            this.currentGoal.stop(this);
            // If a previous goal was running, complete its future with a failure state.
            if (this.currentGoalFuture != null && !this.currentGoalFuture.isDone()) {
                this.currentGoalFuture
                        .complete(new GoalResult(GoalResult.Status.FAILURE, "Goal was cancelled by a new assignment."));
            }
        }
        if (this.currentTask != null) {
            this.currentTask.cancel();
            this.currentTask = null;
        }
        this.blackboard.clear();
        this.currentGoal = goal;
        if (this.currentGoal != null) {
            // Create a new promise for the new goal.
            this.currentGoalFuture = new CompletableFuture<>();
            this.currentGoal.start(this);
            processNextStep();
        } else {
            // If assigning a null goal, return a pre-completed future.
            this.currentGoalFuture = CompletableFuture
                    .completedFuture(new GoalResult(GoalResult.Status.SUCCESS, "No goal assigned."));
        }
        return this.currentGoalFuture;
    }

    public void setCurrentTask(Task task) {
        this.currentTask = task;
        if (this.currentTask != null) {
            this.currentTask.execute(this)
                    .whenCompleteAsync((v, ex) -> {
                        if (ex != null) {
                            plugin.getLogger().log(Level.SEVERE, "Task failed for agent " + getName(), ex.getCause());
                            speak("I ran into an unexpected problem with my task.");
                            // This blackboard key can still be used for internal goal logic, but the final result to the director is now
                            // handled by GoalResult.
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
            GoalResult result = currentGoal.getFinalResult();
            if (currentGoalFuture != null && !currentGoalFuture.isDone()) {
                currentGoalFuture.complete(result);
            }
            // Clean up after completion.
            this.currentGoal = null;
            this.currentGoalFuture = null;
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