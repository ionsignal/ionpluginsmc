package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.messages.AssignGoal;
import com.ionsignal.minecraft.ionnerrus.agent.messages.SetBusyWithDirective;
import com.ionsignal.minecraft.ionnerrus.agent.messages.TaskCompleted;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.Bukkit;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class NerrusAgent {
    private final Persona persona;
    private final IonNerrus plugin;
    private final Blackboard blackboard;
    private final ConcurrentLinkedQueue<Object> messages = new ConcurrentLinkedQueue<>();

    private Task currentTask = null;
    private Goal currentGoal = null;
    private volatile boolean isBusyWithDirective = false;
    private CompletableFuture<GoalResult> currentGoalFuture;

    public NerrusAgent(Persona persona, IonNerrus plugin) {
        this.persona = persona;
        this.plugin = plugin;
        this.blackboard = new Blackboard();
    }

    // Process one message per tick - the ONLY place where agent state is mutated
    public void processMessages() {
        Object message = messages.poll();
        if (message == null) {
            return; // No messages to process
        }
        switch (message) {
            case AssignGoal cmd -> handleAssignGoal(cmd.goal(), cmd.resultFuture());
            case SetBusyWithDirective cmd -> handleSetBusyWithDirective(cmd.isBusy());
            case TaskCompleted event -> handleTaskCompleted(event.error());
            default -> plugin.getLogger().warning("Unknown message type in agent " + getName() + ": " + message.getClass().getSimpleName());
        }
    }

    // Public method now non-blocking, sends message instead of direct state mutation
    public CompletableFuture<GoalResult> assignGoal(Goal goal) {
        CompletableFuture<GoalResult> future = new CompletableFuture<>();
        messages.offer(new AssignGoal(goal, future));
        return future;
    }

    // Public method now non-blocking, sends message instead of direct state mutation
    public void setBusyWithDirective(boolean isBusy) {
        messages.offer(new SetBusyWithDirective(isBusy));
    }

    // Private handler for assign goal command (original assignGoal logic moved here)
    private void handleAssignGoal(Goal goal, CompletableFuture<GoalResult> resultFuture) {
        if (this.currentGoal != null) {
            this.currentGoal.stop(this);
            // If a previous goal was running, handle its future.
            if (this.currentGoalFuture != null && !this.currentGoalFuture.isDone()) {
                if (goal == null) {
                    // This is a hard stop from a command like '/nerrus stop'.
                    // Cancel the future to signal an external interruption.
                    this.currentGoalFuture.cancel(true);
                } else {
                    // A new goal is replacing the old one. The old goal has failed.
                    // Complete normally with a failure state for the ReActDirector to process.
                    this.currentGoalFuture
                            .complete(new GoalResult(GoalResult.Status.FAILURE, "Goal was cancelled by a new assignment."));
                }
            }
        }
        if (this.currentTask != null) {
            this.currentTask.cancel();
            this.currentTask = null;
        }
        this.blackboard.clear();
        this.currentGoal = goal;
        this.currentGoalFuture = resultFuture;
        if (this.currentGoal != null) {
            // Create a new promise for the new goal.
            this.currentGoal.start(this);
            processNextStep();
        } else {
            // This branch is now only for cases where a null goal is assigned when no goal was running.
            if (this.currentGoalFuture != null && !this.currentGoalFuture.isDone()) {
                this.currentGoalFuture.complete(new GoalResult(GoalResult.Status.SUCCESS, "No goal assigned."));
            }
        }
    }

    // Private handler for busy state command (original setBusyWithDirective logic moved here)
    private void handleSetBusyWithDirective(boolean isBusy) {
        this.isBusyWithDirective = isBusy;
    }

    // Private handler for task completion (replaces onTaskComplete method)
    private void handleTaskCompleted(Optional<Throwable> error) {
        if (error.isPresent()) {
            plugin.getLogger().log(Level.SEVERE, "Task failed for agent " + getName(), error.get());
            speak("I ran into an unexpected problem with my task.");
            getBlackboard().put(BlackboardKeys.ISSUE, "TASK_EXCEPTION");
        }
        if (this.currentGoal != null) {
            this.currentTask = null;
            processNextStep();
        }
    }

    // Modified to send TaskCompleted instead of calling onTaskComplete directly
    public void setCurrentTask(Task task) {
        this.currentTask = task;
        if (this.currentTask != null) {
            this.currentTask.execute(this)
                    .whenCompleteAsync((v, ex) -> {
                        // Send message instead of direct method call to avoid race conditions
                        messages.offer(new TaskCompleted(Optional.ofNullable(ex != null ? ex.getCause() : null)));
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

    public boolean isBusyWithDirective() {
        return isBusyWithDirective;
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

    public String getActivityDescription() {
        if (!isBusyWithDirective) {
            return "Idle.";
        }
        if (currentGoal != null) {
            // e.g., "Working on goal: GetBlockGoal"
            return "Working on goal: " + currentGoal.getClass().getSimpleName();
        }
        return "Thinking about the next step.";
    }

    public void speak(String message) {
        persona.speak(message);
    }
}