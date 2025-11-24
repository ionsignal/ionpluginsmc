package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.autonomy.AutonomyEngine;
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
import com.ionsignal.minecraft.ionnerrus.agent.sensory.SensorySystem;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.impl.BukkitSensorySystem;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.entity.Player;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class NerrusAgent {
    private static final int MAX_MEMORY_ENTRIES = 5;

    private final Persona persona;
    private final IonNerrus plugin;
    private final GoalRegistry goalRegistry;
    private final GoalFactory goalFactory;
    private final LLMService llmService;
    private final SensorySystem sensorySystem;
    private final AutonomyEngine autonomyEngine;
    private final ConcurrentLinkedQueue<Object> messages = new ConcurrentLinkedQueue<>();
    private final Deque<GoalContext> goalStack = new ConcurrentLinkedDeque<>();
    private final LinkedList<String> actionHistory = new LinkedList<>();

    private Task currentTask = null;
    private CompletableFuture<GoalResult> topLevelGoalFuture;
    private ReActDirector currentDirector = null;
    private GoalContext currentContext = null;

    private volatile boolean isBusyWithDirective = false;

    /**
     * An immutable record to hold the state of a single goal on the stack. where each goal gets its own
     * isolated message queue.
     */
    public record GoalContext(
            Goal goal,
            Object parameters,
            ConcurrentLinkedQueue<Object> mailbox) {
        /**
         * Posts a message to this context's mailbox.
         * Thread-safe and can be called from any thread.
         */
        public void postMessage(Object payload) {
            mailbox.offer(payload);
        }
    }

    public NerrusAgent(Persona persona, IonNerrus plugin, GoalRegistry goalRegistry, GoalFactory goalFactory, LLMService llmService) {
        this.persona = persona;
        this.plugin = plugin;
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
        this.autonomyEngine = new AutonomyEngine(this);
        this.sensorySystem = new BukkitSensorySystem(this);
    }

    /**
     * Bootstraps the agent's decision engine and should be called once after the agent is fully
     * initialized and spawned.
     */
    public void start() {
        // Trigger the first step. If no goal is assigned, this will
        // fall through to the AutonomyEngine -> IdleGoal transition.
        processNextStep();
    }

    /**
     * Processes pending messages for the agent using a priority-based processing loop to ensure state
     * consistency.
     */
    public void processMessages() {
        // Ensures working memory is updated every tick regardless of agent activity.
        if (sensorySystem != null) {
            sensorySystem.tick();
        }
        // Handle Interrupts (AssignGoal, etc.) immediately
        Object globalHead = messages.peek();
        if (isInterruptMessage(globalHead)) {
            Object msg = messages.poll();
            handleGlobalMessage(msg);
            return; // Interrupts invalidate current state processing, so we stop here for this tick.
        }
        // Goal Data Processing: Handle mailbox messages (Results from skills)
        if (currentContext != null && currentContext.mailbox() != null) {
            // Process one message per tick. This is sufficient for the Request/Response pattern of Tasks
            // and prevents a flood of data from starving the global queue.
            Object goalMessage = currentContext.mailbox().poll();
            if (goalMessage != null) {
                try {
                    handleGoalMessage(goalMessage);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Error processing goal message for agent " + getName(), e);
                }
            }
        }
        // Handle TaskCompleted and other non-interrupt global messages
        Object globalMsg = messages.poll();
        if (globalMsg != null) {
            // The Agent is about to make a decision based on incomplete state.
            if (globalMsg instanceof TaskCompleted && currentContext != null && !currentContext.mailbox().isEmpty()) {
                String pendingMsgType = currentContext.mailbox().peek().getClass().getSimpleName();
                throw new IllegalStateException(String.format(
                        "Agent '%s' received TaskCompleted while Goal Mailbox still has pending messages! "
                                + "Next pending: %s. Tasks must not stream data faster than 1 msg/tick.",
                        getName(), pendingMsgType));
            }
            handleGlobalMessage(globalMsg);
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
            this.currentContext = new GoalContext(
                    goal,
                    parameters,
                    new ConcurrentLinkedQueue<>());
            this.topLevelGoalFuture = resultFuture;
            this.currentContext.goal().start(this);
            processNextStep();
        } else {
            plugin.getLogger().info(String.format("[%s] Goal assignment was null. Agent is now idle.", getName()));
            this.currentContext = null;
            this.topLevelGoalFuture = null;
            processNextStep();
        }
    }

    private boolean isInterruptMessage(Object msg) {
        return msg instanceof AssignGoal ||
                msg instanceof AssignDirective ||
                msg instanceof SetBusyWithDirective;
    }

    private void handleGlobalMessage(Object msg) {
        try {
            switch (msg) {
                case AssignGoal cmd -> handleAssignGoal(cmd.goal(), cmd.parameters(), cmd.resultFuture());
                case AssignDirective cmd -> handleAssignDirective(cmd.directive(), cmd.requester());
                case SetBusyWithDirective cmd -> handleSetBusyWithDirective(cmd.isBusy());
                case TaskCompleted event -> handleTaskCompleted(event.task(), event.error());
                default -> plugin.getLogger()
                        .warning("Unknown message type in agent " + getName() + ": " + msg.getClass().getSimpleName());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error processing global message for agent " + getName(), e);
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

    private void handleTaskCompleted(Task finishedTask, Optional<Throwable> error) {
        if (this.currentTask != finishedTask) {
            plugin.getLogger().info(String.format("[%s] Ignoring completion of stale task: %s (Current: %s)",
                    getName(),
                    finishedTask.getClass().getSimpleName(),
                    this.currentTask != null ? this.currentTask.getClass().getSimpleName() : "null"));
            return;
        }
        String taskName = currentTask != null ? currentTask.getClass().getSimpleName() : "Unknown Task";
        if (error.isPresent()) {
            // Filter out CancellationException to prevent false positive error logs
            Throwable ex = error.get();
            if (ex instanceof CancellationException) {
                plugin.getLogger().info(String.format("[%s] Task %s was cancelled.", getName(), taskName));
            } else {
                plugin.getLogger().log(Level.SEVERE, String.format("[%s] Task %s failed with exception:", getName(), taskName), ex);
                speak("I ran into an unexpected problem with my task.");
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
                        messages.offer(new TaskCompleted(task, Optional.ofNullable(ex != null ? ex.getCause() : null)));
                    }, IonNerrus.getInstance().getMainThreadExecutor());
        }
    }

    private void processNextStep() {
        // If the agent has no active goal and no stack, consult the AutonomyEngine.
        if (currentContext == null && goalStack.isEmpty()) {
            if (autonomyEngine != null) {
                Goal ambientGoal = autonomyEngine.suggestGoal(sensorySystem.getWorkingMemory()).orElse(null);
                if (ambientGoal != null) {
                    plugin.getLogger().info(String.format("[%s] Autonomy engine suggested goal: %s", getName(),
                            ambientGoal.getClass().getSimpleName()));
                    // Assign the goal via the message loop to ensure consistent state transitions.
                    assignGoal(ambientGoal, null);
                    return;
                }
            }
        }
        if (currentContext == null) {
            return;
        }
        plugin.getLogger().info(String.format("[%s] Processing goal: %s", getName(),
                currentContext.goal().getClass().getSimpleName()));
        currentContext.goal().process(this);
        if (currentTask != null) {
            return; // A new task was dispatched, wait for it to complete.
        }
        if (currentContext.goal().isFinished()) {
            plugin.getLogger().info(
                    String.format("[%s] Goal %s reported as finished.", getName(),
                            currentContext.goal().getClass().getSimpleName()));
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
                    this.currentContext = new GoalContext(
                            subGoal,
                            prerequisite.parameters(),
                            new ConcurrentLinkedQueue<>());
                    this.currentContext.goal().start(this);
                    processNextStep(); // Immediately process the new sub-goal.
                } catch (Exception e) {
                    String errorMessage = "Failed to create prerequisite goal '" + prerequisite.goalName() + "': " + e.getMessage();
                    plugin.getLogger().log(Level.SEVERE, String.format("[%s] %s", getName(), errorMessage), e);
                    handleGoalCompletion(new GoalResult.Failure(errorMessage));
                }
            }
            default -> {
                if (goalStack.isEmpty()) {
                    // This was the top-level goal. Complete its future and we're done.
                    plugin.getLogger().info(String.format("[%s] Top-level goal %s finished. Result: %s", getName(), completedGoalName,
                            result.getClass().getSimpleName()));
                    if (topLevelGoalFuture != null && !topLevelGoalFuture.isDone()) {
                        topLevelGoalFuture.complete(result);
                    }
                    currentContext = null;
                    topLevelGoalFuture = null;
                    processNextStep();
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
    public Goal getCurrentGoal() {
        return currentContext != null ? currentContext.goal() : null;
    }

    public Task getCurrentTask() {
        return currentTask;
    }

    public SensorySystem getSensorySystem() {
        return sensorySystem;
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
    public void postMessage(Object contextToken, Object payload) {
        if (currentContext != null && currentContext.mailbox() != null) {
            if (currentContext.goal().getContextToken() == contextToken) {
                currentContext.mailbox().offer(payload);
            } else {
                plugin.getLogger().warning("Attempted to post message to goal, but context token does not match. " +
                        "Message discarded: " + payload.getClass().getSimpleName());
            }
        } else {
            plugin.getLogger().warning("Attempted to post message to goal, but no active context exists. " +
                    "Message discarded: " + payload.getClass().getSimpleName());
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