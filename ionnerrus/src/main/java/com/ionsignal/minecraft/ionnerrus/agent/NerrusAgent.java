package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
<<<<<<< HEAD
// import com.ionsignal.minecraft.ionnerrus.agent.autonomy.AutonomyEngine;
=======
import com.ionsignal.minecraft.ionnerrus.network.NetworkBroadcaster;
import com.ionsignal.minecraft.ionnerrus.agent.autonomy.AutonomyEngine;
>>>>>>> bc098a3 (feat(ioncore, ionnerrus): implement websocket networking and agent telemetry)
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionController;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
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
import com.ionsignal.minecraft.ionnerrus.agent.messages.SystemError;
import com.ionsignal.minecraft.ionnerrus.agent.messages.TaskCompleted;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.SensorySystem;
import com.ionsignal.minecraft.ionnerrus.agent.sensory.impl.BukkitSensorySystem;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
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
import java.util.function.Function;
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
<<<<<<< HEAD
    // private final AutonomyEngine autonomyEngine;
=======
    private final AutonomyEngine autonomyEngine;
    private final NetworkBroadcaster broadcaster;
>>>>>>> bc098a3 (feat(ioncore, ionnerrus): implement websocket networking and agent telemetry)
    private final ConcurrentLinkedQueue<Object> messages = new ConcurrentLinkedQueue<>();
    private final Deque<GoalContext> goalStack = new ConcurrentLinkedDeque<>();
    private final LinkedList<String> actionHistory = new LinkedList<>();

    private Task currentTask = null;
    private CompletableFuture<GoalResult> topLevelGoalFuture;
    private ReActDirector currentDirector = null;
    private GoalContext currentContext = null;

    private volatile boolean isBusyWithDirective = false;

    // Throttling state
    private int tickCounter = 0;
    private static final int TELEMETRY_INTERVAL = 10; // Send updates every 10 ticks (0.5s)

    /**
     * An immutable record to hold the state of a single goal on the stack. where each goal gets its own
     * isolated message queue and execution controller.
     */
    public record GoalContext(
            Goal goal,
            Object parameters,
            ExecutionController controller,
            ConcurrentLinkedQueue<Object> mailbox) {

        public ExecutionToken token() {
            return controller.getToken();
        }

        /**
         * Posts a message to this context's mailbox.
         * Thread-safe and can be called from any thread.
         */
        public void postMessage(Object payload) {
            mailbox.offer(payload);
        }
    }

    public NerrusAgent(Persona persona, IonNerrus plugin, GoalRegistry goalRegistry, GoalFactory goalFactory, LLMService llmService, NetworkBroadcaster broadcaster) {
        this.persona = persona;
        this.plugin = plugin;
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
<<<<<<< HEAD
        // this.autonomyEngine = new AutonomyEngine(this);
=======
        this.broadcaster = broadcaster;
        this.autonomyEngine = new AutonomyEngine(this);
>>>>>>> bc098a3 (feat(ioncore, ionnerrus): implement websocket networking and agent telemetry)
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
        // Cancel existing execution hierarchy
        if (this.currentContext != null) {
            plugin.getLogger().info(String.format("[%s] Cancelling current goal context: %s",
                    getName(), currentContext.goal().getClass().getSimpleName()));
            this.currentContext.controller().cancel();
            this.currentContext.goal().stop(this);
            this.currentContext.controller().close();
        }
        if (!goalStack.isEmpty()) {
            plugin.getLogger().info(String.format("[%s] Cancelling goal stack (size: %d)", getName(), goalStack.size()));
            for (GoalContext context : goalStack) {
                context.controller().cancel();
                context.goal().stop(this);
                context.controller().close();
            }
            this.goalStack.clear();
        }
        // Handle previous top-level future
        if (this.topLevelGoalFuture != null && !this.topLevelGoalFuture.isDone()) {
            if (goal == null) {
                plugin.getLogger().info(String.format("[%s] Cancelling top-level goal future due to stop command.", getName()));
                this.topLevelGoalFuture.cancel(true);
            } else {
                plugin.getLogger().info(String.format("[%s] Failing previous top-level goal future due to new assignment.", getName()));
                this.topLevelGoalFuture.complete(new GoalResult.Failure("Goal was cancelled by a new assignment."));
            }
        }
        // Set up the new top-level goal
        if (goal != null) {
            plugin.getLogger().info(String.format("[%s] Starting new top-level goal: %s", getName(), goalName));
            // Create Root Controller
            ExecutionController rootController = ExecutionController.create();
            this.currentContext = new GoalContext(
                    goal,
                    parameters,
                    rootController,
                    new ConcurrentLinkedQueue<>());
            this.topLevelGoalFuture = resultFuture;
            this.currentContext.goal().start(this, this.currentContext.token());
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
            // Forward the completion event to the Goal for loop back, handle failure, or finish
            TaskCompleted completionEvent = new TaskCompleted(finishedTask, error);
            try {
                this.currentContext.goal().onMessage(this, completionEvent, this.currentContext.token());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in Goal.onMessage during Task completion", e);
            }
            // Clear the task reference *after* notification
            this.currentTask = null;
            // Trigger the next cognitive step (tick based)
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
            // Give the active task first opportunity to handle the message allowing our `Task` implementations
            // to handle tactical logic (like Repathing.)
            if (currentTask != null) {
                currentTask.onMessage(this, payload, currentContext.token());
            }
            currentGoal.onMessage(this, payload, currentContext.token());
            // Check for completion after processing a message such message-driven goals finish naturally.
            if (currentGoal.isFinished()) {
                handleGoalCompletion(currentGoal.getFinalResult());
            }
        }
    }

    public void setCurrentTask(Task task) {
        this.currentTask = task;
        if (this.currentTask != null) {
            plugin.getLogger().info(String.format("[%s] Executing new task: %s", getName(), task.getClass().getSimpleName()));
            // Pass the ExecutionToken to the task assuming currentContext is not null here because tasks are
            // assigned by Goals. If currentContext is null (legacy/edge case), we might need a fallback, but
            // strictly speaking, tasks belong to goals now.
            ExecutionToken token = (currentContext != null) ? currentContext.token() : null;
            if (token == null) {
                plugin.getLogger().warning("Task assigned without an active GoalContext! Cancellation may not work correctly.");
            }
            this.currentTask.execute(this, token)
                    .whenCompleteAsync((v, ex) -> {
                        // Send message instead of direct method call to avoid race conditions
                        messages.offer(new TaskCompleted(task, Optional.ofNullable(ex != null ? ex.getCause() : null)));
                    }, IonNerrus.getInstance().getMainThreadExecutor());
        }
    }

    private void processNextStep() {
        // If the agent has no active goal and no stack, consult the AutonomyEngine.
        // TODO: INVESTIGATE AND RE-ENABLE AT A LATER DATE
        // THIS IS NOT CURRENTLY COMPATIBLE OR PROPERLY IMPLEMENTED
        // if (currentContext == null && goalStack.isEmpty()) {
        // if (autonomyEngine != null) {
        // Goal ambientGoal = autonomyEngine.suggestGoal(sensorySystem.getWorkingMemory()).orElse(null);
        // if (ambientGoal != null) {
        // plugin.getLogger().info(String.format("[%s] Autonomy engine suggested goal: %s", getName(),
        // ambientGoal.getClass().getSimpleName()));
        // // Assign the goal via the message loop to ensure consistent state transitions.
        // assignGoal(ambientGoal, null);
        // return;
        // }
        // }
        // }
        if (currentContext == null) {
            return;
        }
        plugin.getLogger().info(String.format("[%s] Processing goal: %s", getName(),
                currentContext.goal().getClass().getSimpleName()));
        currentContext.goal().process(this, currentContext.token());
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
        // Broadcast Goal Result
        if (broadcaster != null) {
            String status = (result instanceof GoalResult.Success) ? "COMPLETED" : "FAILED";
            String message = result.message();
            broadcaster.broadcastGoalEvent(this, status, completedGoalName, message);
            
            // Also trigger inventory update as goals often change inventory
            broadcaster.broadcastInventory(this);
        }
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
                // Create Child Controller linked to current token
                ExecutionController childController = ExecutionController.createChild(currentContext.token());
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
                            childController,
                            new ConcurrentLinkedQueue<>());
                    this.currentContext.goal().start(this, this.currentContext.token());
                    processNextStep(); // Immediately process the new sub-goal.
                } catch (Exception e) {
                    String errorMessage = "Failed to create prerequisite goal '" + prerequisite.goalName() + "': " + e.getMessage();
                    plugin.getLogger().log(Level.SEVERE, String.format("[%s] %s", getName(), errorMessage), e);
                    handleGoalCompletion(new GoalResult.Failure(errorMessage));
                }
            }
            default -> {
                // Goal Finished (Success or Failure)
                // Detach controller from parent to prevent memory leaks
                // Terminate the Execution Lifecycle.
                // This triggers the ExecutionToken to fire all 'onCancel' callbacks.
                // Crucially, this forces BukkitPhysicalBody to stop movement immediately.
                currentContext.controller().cancel();
                // Detach controller from parent to prevent memory leaks.
                currentContext.controller().close();
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
                    this.currentContext.goal().resume(this, result, this.currentContext.token());
                    processNextStep(); // Immediately process the resumed parent goal.
                }
            }
        }
    }

    public void tick() {
        // 1. Standard processing
        processMessages();
        
        // 2. Network Telemetry Throttling
        tickCounter++;
        if (tickCounter >= TELEMETRY_INTERVAL) {
            tickCounter = 0;
            if (broadcaster != null && persona.isSpawned()) {
                broadcaster.broadcastTelemetry(this);
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

    public String getCurrentGoalName() {
        return (currentContext != null) ? currentContext.goal().getClass().getSimpleName() : "Idle";
    }
    
    public String getCurrentTaskName() {
        return (currentTask != null) ? currentTask.getClass().getSimpleName() : "None";
    }
    
    public List<String> getGoalStackNames() {
        if (goalStack.isEmpty()) return Collections.emptyList();
        return goalStack.stream()
            .map(ctx -> ctx.goal().getClass().getSimpleName())
            .collect(Collectors.toList());
    }

    /**
     * Posts a message to the current goal's mailbox from an async operation.
     * 
     * The message is queued ONLY if the provided ExecutionToken matches the currently active
     * goal context. This prevents stale async callbacks (from cancelled tasks) from corrupting
     * the state of a new, unrelated goal.
     * 
     * @param token
     *            The execution token bound to the operation producing this message.
     * @param payload
     *            The message payload.
     */
    public void postMessage(ExecutionToken token, Object payload) {
        // We check ID equality to ensure it's the same logical execution context
        // Capture the context reference to prevent race conditions if it becomes null mid-execution
        GoalContext context = this.currentContext;
        if (context != null && context.mailbox() != null) {
            // Validate that the message belongs to the currently active goal lifecycle
            if (context.token().getId().equals(token.getId())) {
                context.mailbox().offer(payload);
            } else {
                // Debug-level logging for stale messages is preferred over Warnings.
                // Stale messages are a normal occurrence when a goal is cancelled (e.g., by an interrupt)
                // but an async task from that goal completes a few milliseconds later.
                if (plugin.getLogger().isLoggable(Level.FINE)) {
                    plugin.getLogger().fine(String.format(
                            "[%s] Stale message discarded (Token Mismatch). Msg: %s",
                            getName(), payload.getClass().getSimpleName()));
                }
            }
        } else {
            // If context is null, the agent is likely shutting down or between states.
            if (plugin.getLogger().isLoggable(Level.FINE)) {
                plugin.getLogger().fine(String.format(
                        "[%s] Message discarded (No Context). Msg: %s",
                        getName(), payload.getClass().getSimpleName()));
            }
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

    /**
     * Executes a skill and automatically bridges the result to the mailbox.
     * This enforces the "Trigger -> Bridge -> React" pattern using ExecutionTokens.
     *
     * @param skill
     *            The skill to execute.
     * @param token
     *            The current execution token.
     * @param mapper
     *            A function that converts the Skill result into a Message Record.
     * @param <R>
     *            The return type of the Skill.
     */
    public <R> void executeSkill(Skill<R> skill, ExecutionToken token, Function<R, Object> mapper) {
        // We capture the token passed in (which should be the current context's token)
        // and pass it to the async callback to ensure the response is routed correctly.
        skill.execute(this, token).whenCompleteAsync((result, ex) -> {
            // 1. Handle Crashes/Exceptions
            if (ex != null) {
                Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                this.postMessage(token, new SystemError(cause));
                return;
            }
            // 2. Map Result to Message
            try {
                Object message = mapper.apply(result);
                // 3. Post to Mailbox using the captured token
                this.postMessage(token, message);
            } catch (Exception mapEx) {
                plugin.getLogger().log(Level.SEVERE, "Error mapping skill result to message", mapEx);
                this.postMessage(token, new SystemError(mapEx));
            }
        }, plugin.getMainThreadExecutor());
    }
}