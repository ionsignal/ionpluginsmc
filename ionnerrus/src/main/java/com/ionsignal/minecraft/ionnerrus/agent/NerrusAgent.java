package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalPrerequisite;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.agent.llm.ReActDirector;
import com.ionsignal.minecraft.ionnerrus.agent.messages.AssignDirective;
import com.ionsignal.minecraft.ionnerrus.agent.messages.AssignGoal;
import com.ionsignal.minecraft.ionnerrus.agent.messages.SetBusyWithDirective;
import com.ionsignal.minecraft.ionnerrus.agent.messages.TaskCompleted;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.entity.Player;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class NerrusAgent {
    private static final int MAX_MEMORY_ENTRIES = 5;

    private final Persona persona;
    private final IonNerrus plugin;
    private final GoalRegistry goalRegistry;
    private final GoalFactory goalFactory;
    private final LLMService llmService;
    private final ConcurrentLinkedQueue<Object> messages = new ConcurrentLinkedQueue<>();
    private final Deque<GoalContext> goalStack = new ArrayDeque<>();
    private final LinkedList<String> actionHistory = new LinkedList<>();

    private Task currentTask = null;
    private CompletableFuture<GoalResult> topLevelGoalFuture;
    private ReActDirector currentDirector = null;
    private GoalContext currentContext = null;

    private volatile boolean isBusyWithDirective = false;

    /**
     * An immutable record to hold the state of a single goal on the stack. where each goal gets its own
     * isolated blackboard and private message queue.
     */
    private record GoalContext(
            Goal goal,
            Object parameters,
            Blackboard blackboard, // Keep for CraftItemGoal compatibility
            ConcurrentLinkedQueue<Object> mailbox // NEW: Per-goal message queue
    ) {
    }

    public NerrusAgent(Persona persona, IonNerrus plugin, GoalRegistry goalRegistry, GoalFactory goalFactory, LLMService llmService) {
        this.persona = persona;
        this.plugin = plugin;
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
    }

    // Process one message per tick with priority-based message processing (goal mailbox first)
    public void processMessages() {
        // Process goal-specific messages first
        if (currentContext != null && currentContext.mailbox() != null) {
            Object goalMessage = currentContext.mailbox().poll();
            if (goalMessage != null) {
                try {
                    handleGoalMessage(goalMessage);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Error processing goal message for agent " + getName(), e);
                }
                return; // Process one message per tick
            }
        }
        // Process global agent messages
        Object message = messages.poll();
        if (message == null) {
            return; // No messages to process
        }
        switch (message) {
            case AssignGoal cmd -> handleAssignGoal(cmd.goal(), cmd.parameters(), cmd.resultFuture());
            case AssignDirective cmd -> handleAssignDirective(cmd.directive(), cmd.requester());
            case SetBusyWithDirective cmd -> handleSetBusyWithDirective(cmd.isBusy());
            case TaskCompleted event -> handleTaskCompleted(event.error());
            default -> plugin.getLogger().warning("Unknown message type in agent " + getName() + ": " + message.getClass().getSimpleName());
        }
    }

    public CompletableFuture<GoalResult> assignGoal(Goal goal, Object parameters) {
        CompletableFuture<GoalResult> future = new CompletableFuture<>();
        messages.offer(new AssignGoal(goal, parameters, future));
        return future;
    }

    public void assignDirective(String directive, Player requester) {
        messages.offer(new AssignDirective(directive, requester));
    }

    public void setBusyWithDirective(boolean isBusy) {
        messages.offer(new SetBusyWithDirective(isBusy));
    }

    private void handleAssignGoal(Goal goal, Object parameters, CompletableFuture<GoalResult> resultFuture) {
        String goalName = goal != null ? goal.getClass().getSimpleName() : "null";
        plugin.getLogger().info(String.format("[%s] Received AssignGoal: %s", getName(), goalName));
        // Stop and clear the entire goal stack, starting with the current goal.
        if (this.currentContext != null || !goalStack.isEmpty()) {
            plugin.getLogger().info(String.format("[%s] Clearing existing goal stack (current: %s, stack size: %d)",
                    getName(),
                    currentContext != null ? currentContext.goal().getClass().getSimpleName() : "none",
                    goalStack.size()));
            if (this.currentContext != null) {
                this.currentContext.goal().stop(this);
            }
            for (GoalContext context : goalStack) {
                context.goal().stop(this);
            }
            this.goalStack.clear();
        }
        // If a previous top-level goal was running, handle its future.
        if (this.topLevelGoalFuture != null && !this.topLevelGoalFuture.isDone()) {
            if (goal == null) {
                plugin.getLogger().info(String.format("[%s] Cancelling top-level goal future due to stop command.", getName()));
                this.topLevelGoalFuture.cancel(true);
            } else {
                plugin.getLogger().info(String.format("[%s] Failing previous top-level goal future due to new assignment.", getName()));
                this.topLevelGoalFuture.complete(new GoalResult.Failure("Goal was cancelled by a new assignment."));
            }
        }
        // Cancel any active task.
        if (this.currentTask != null) {
            plugin.getLogger().info(String.format("[%s] Cancelling active task: %s", getName(), currentTask.getClass().getSimpleName()));
            this.currentTask.cancel();
            this.currentTask = null;
        }
        // Set up the new top-level goal.
        if (goal != null) {
            plugin.getLogger().info(String.format("[%s] Starting new top-level goal: %s", getName(), goalName));
            // Added fresh mailbox for new goal
            this.currentContext = new GoalContext(
                    goal,
                    parameters,
                    new Blackboard(), // Keep for CraftItemGoal compatibility
                    new ConcurrentLinkedQueue<>() // Fresh mailbox for new goal
            );
            this.topLevelGoalFuture = resultFuture;
            this.currentContext.goal().start(this);
            processNextStep();
        } else {
            plugin.getLogger().info(String.format("[%s] Goal assignment was null. Agent is now idle.", getName()));
            this.currentContext = null;
            this.topLevelGoalFuture = null;
        }
    }

    private void handleAssignDirective(String directive, Player requester) {
        if (this.currentDirector != null) {
            this.currentDirector.cancel();
            this.currentDirector = null;
        }
        this.currentDirector = new ReActDirector(this, goalRegistry, goalFactory, llmService);
        this.currentDirector.executeDirective(directive, requester);
    }

    private void handleSetBusyWithDirective(boolean isBusy) {
        this.isBusyWithDirective = isBusy;
        if (!isBusy) {
            this.currentDirector = null;
        }
    }

    private void handleTaskCompleted(Optional<Throwable> error) {
        String taskName = currentTask != null ? currentTask.getClass().getSimpleName() : "Unknown Task";
        if (error.isPresent()) {
            plugin.getLogger().log(Level.SEVERE, String.format("[%s] Task %s failed with exception:", getName(), taskName), error.get());
            speak("I ran into an unexpected problem with my task.");
            if (getBlackboard() != null) {
                getBlackboard().put(BlackboardKeys.ISSUE, "TASK_EXCEPTION");
            }
        } else {
            plugin.getLogger().info(String.format("[%s] Task %s completed successfully.", getName(), taskName));
        }
        if (this.currentContext != null) {
            this.currentTask = null;
            processNextStep();
        }
    }

    /**
     * Handles a message dispatched to the current goal. This method is always called on the main server
     * thread, ensuring thread-safe state mutation.
     *
     * @param payload
     *            The message payload to be dispatched to the goal.
     */
    private void handleGoalMessage(Object payload) {
        Goal currentGoal = getCurrentGoal();
        if (currentGoal != null) {
            currentGoal.onMessage(this, payload);
        }
    }

    public void setCurrentTask(Task task) {
        this.currentTask = task;
        if (this.currentTask != null) {
            plugin.getLogger().info(String.format("[%s] Executing new task: %s", getName(), task.getClass().getSimpleName()));
            this.currentTask.execute(this)
                    .whenCompleteAsync((v, ex) -> {
                        // Send message instead of direct method call to avoid race conditions
                        messages.offer(new TaskCompleted(Optional.ofNullable(ex != null ? ex.getCause() : null)));
                    }, IonNerrus.getInstance().getMainThreadExecutor());
        }
    }

    private void processNextStep() {
        if (currentContext == null) {
            return;
        }
        plugin.getLogger()
                .info(String.format("[%s] Processing goal: %s", getName(), currentContext.goal().getClass().getSimpleName()));
        currentContext.goal().process(this);
        if (currentTask != null) {
            return; // A new task was dispatched, wait for it to complete.
        }
        if (currentContext.goal().isFinished()) {
            plugin.getLogger().info(
                    String.format("[%s] Goal %s reported as finished.", getName(), currentContext.goal().getClass().getSimpleName()));
            handleGoalCompletion(currentContext.goal().getFinalResult());
        }
    }

    private void handleGoalCompletion(GoalResult result) {
        String completedGoalName = currentContext.goal().getClass().getSimpleName();
        plugin.getLogger().info(String.format("[%s] Handling completion of %s with result: %s", getName(), completedGoalName,
                result.getClass().getSimpleName()));
        switch (result) {
            case GoalResult.PrerequisiteResult p -> {
                GoalPrerequisite prerequisite = p.prerequisite();
                plugin.getLogger().info(String.format("[%s] Goal requires prerequisite: %s with params %s", getName(),
                        prerequisite.goalName(), prerequisite.parameters()));
                // Create a temporary goal instance just to get its class for the check.
                Goal tempGoalForCheck = goalFactory.createGoal(prerequisite.goalName(), prerequisite.parameters());
                // Circular dependency check.
                for (GoalContext contextInStack : goalStack) {
                    if (contextInStack.goal().getClass().equals(tempGoalForCheck.getClass())
                            && Objects.equals(contextInStack.parameters(), prerequisite.parameters())) {
                        String errorMessage = "Detected circular goal dependency: " + prerequisite.goalName();
                        plugin.getLogger().severe(String.format("[%s] %s", getName(), errorMessage));
                        handleGoalCompletion(new GoalResult.Failure(errorMessage));
                        return;
                    }
                }
                // Push current goal onto the stack.
                plugin.getLogger().info(String.format("[%s] Pushing %s to stack. Stack size is now %d.", getName(),
                        currentContext.goal().getClass().getSimpleName(), goalStack.size() + 1));
                goalStack.push(this.currentContext);
                // Create and start the new sub-goal.
                try {
                    Goal subGoal = goalFactory.createGoal(prerequisite.goalName(), prerequisite.parameters());
                    plugin.getLogger().info(String.format("[%s] Starting new sub-goal: %s", getName(), subGoal.getClass().getSimpleName()));
                    // REFACTOR: Added fresh mailbox for sub-goal
                    this.currentContext = new GoalContext(
                            subGoal,
                            prerequisite.parameters(),
                            new Blackboard(), // Keep for CraftItemGoal compatibility
                            new ConcurrentLinkedQueue<>() // NEW: Fresh mailbox for sub-goal
                    );
                    this.currentContext.goal().start(this);
                    processNextStep(); // Immediately process the new sub-goal.
                } catch (Exception e) {
                    String errorMessage = "Failed to create prerequisite goal '" + prerequisite.goalName() + "': " + e.getMessage();
                    plugin.getLogger().log(Level.SEVERE, String.format("[%s] %s", getName(), errorMessage), e);
                    handleGoalCompletion(new GoalResult.Failure(errorMessage));
                }
            }
            default -> { // Handles Success and Failure
                if (goalStack.isEmpty()) {
                    // This was the top-level goal. Complete its future and we're done.
                    plugin.getLogger().info(String.format("[%s] Top-level goal %s finished. Result: %s", getName(), completedGoalName,
                            result.getClass().getSimpleName()));
                    if (topLevelGoalFuture != null && !topLevelGoalFuture.isDone()) {
                        topLevelGoalFuture.complete(result);
                    }
                    currentContext = null;
                    topLevelGoalFuture = null;
                } else {
                    // This was a sub-goal. Pop the parent and resume it.
                    GoalContext parentContext = goalStack.pop();
                    plugin.getLogger().info(String.format("[%s] Sub-goal %s finished. Popping %s from stack. Resuming parent.",
                            getName(), completedGoalName, parentContext.goal().getClass().getSimpleName()));
                    this.currentContext = parentContext;
                    this.currentContext.goal().resume(this, result);
                    processNextStep(); // Immediately process the resumed parent goal.
                }
            }
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

    @Nullable
    public Blackboard getBlackboard() {
        return currentContext != null ? currentContext.blackboard() : null;
    }

    @Nullable
    public Goal getCurrentGoal() {
        return currentContext != null ? currentContext.goal() : null;
    }

    public Task getCurrentTask() {
        return currentTask;
    }

    public String getActivityDescription() {
        if (!isBusyWithDirective) {
            return "Idle.";
        }
        Goal goal = getCurrentGoal();
        if (goal != null) {
            String stackStr = goalStack.stream()
                    .map(ctx -> ctx.goal().getClass().getSimpleName())
                    .collect(Collectors.joining(" -> "));
            return String.format("Working on goal: %s (Stack: [%s])", goal.getClass().getSimpleName(), stackStr);
        }
        return "Thinking about the next step.";
    }

    public void speak(String message) {
        persona.speak(message);
    }

    /**
     * Posts a message to the current goal's mailbox from an async operation where the message will be
     * queued in the goal's private mailbox and dispatched on the main server thread during the next
     * tick.
     * 
     * This method is thread-safe and intended for use by async callbacks (e.g., task completions). Note
     * that if no goal is currently active, the message is discarded with a warning.
     * 
     * @param payload
     *            The message payload (typically a result record from skill execution).
     */
    public void postMessage(Object payload) {
        if (currentContext != null && currentContext.mailbox() != null) {
            currentContext.mailbox().offer(payload);
        } else {
            plugin.getLogger().warning("Attempted to post message to goal, but no active context exists. Message discarded: "
                    + payload.getClass().getSimpleName());
        }
    }

    /**
     * Records a memory of a completed action, maintaining a fixed-size history.
     * 
     * @param memory
     *            A string describing the action's outcome.
     */
    public void recordAction(String memory) {
        actionHistory.addFirst(memory);
        while (actionHistory.size() > MAX_MEMORY_ENTRIES) {
            actionHistory.removeLast();
        }
    }

    /**
     * Gets the simple class name of the next message in the queue providing safe access to message
     * queue state for debug visualization.
     * 
     * @return The simple class name of the next message, or null if queue is empty.
     */
    public String getNextMessageType() {
        Object nextMessage = messages.peek();
        return nextMessage != null ? nextMessage.getClass().getSimpleName() : null;
    }

    /**
     * Retrieves the agent's recent action history.
     * 
     * @return An unmodifiable list of the last few action outcomes.
     */
    public List<String> getActionHistory() {
        return Collections.unmodifiableList(actionHistory);
    }

    /**
     * Gets the size of the current goal's mailbox for debug visualization.
     * 
     * WARNING: size() on ConcurrentLinkedQueue is O(n), suitable for debugging but not for hot paths.
     * 
     * @return The number of pending messages in the goal mailbox, or 0 if no goal is active.
     */
    public int getGoalMailboxSize() {
        if (currentContext != null && currentContext.mailbox() != null) {
            return currentContext.mailbox().size();
        }
        return 0;
    }
}