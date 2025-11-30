package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GatherBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.messages.TaskCompleted;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.GatherBlockTask;

import org.bukkit.Location;
import org.bukkit.Material;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class GatherBlockGoal implements Goal {
    // Internal FSM States
    private enum State {
        STARTING, VERIFYING_INVENTORY, GATHERING_BATCH, COMPLETED, FAILED
    }

    // Messages (Data Carriers)
    public record InventoryCountResult(int count) {
    }

    public record GatherAttemptResult(GatherResult status) {
    }

    public enum GatherResult {
        SUCCESS, NO_BLOCKS_IN_RANGE, NO_REACHABLE_BLOCKS_IN_RANGE, FAILED_TO_COLLECT
    }

    private final Logger logger;
    private final Set<Material> materials;
    private final GatherBlockParameters params;

    // State Data
    private final Set<Location> attemptedLocations = new HashSet<>();
    private State state = State.STARTING;
    private int currentCount = 0;
    private String failureReason = null;

    public GatherBlockGoal(Set<Material> materials, GatherBlockParameters params) {
        this.materials = materials;
        this.params = params;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public void start(NerrusAgent agent, ExecutionToken token) {
        this.attemptedLocations.clear();
        this.state = State.VERIFYING_INVENTORY;
        agent.speak("Okay, I'll get " + params.quantity() + " " + params.groupName() + ".");

        // Kick off the loop: Check what we already have.
        dispatchInventoryCheck(agent);
    }

    @Override
    public void process(NerrusAgent agent, ExecutionToken token) {
        // No-Op: Logic is now entirely event-driven in onMessage.
        // We do not poll state here.
    }

    @Override
    public void onMessage(NerrusAgent agent, Object message, ExecutionToken token) {
        switch (message) {
            // Data Update: Received inventory count
            case InventoryCountResult result -> {
                this.currentCount = result.count();
                logger.info(String.format("[%s] Inventory check: %d/%d %s",
                        agent.getName(), currentCount, params.quantity(), params.groupName()));
            }
            // Data Update: Received feedback from the Gather Task (Monolithic script feedback)
            case GatherAttemptResult result -> handleGatherFeedback(result);
            // Lifecycle Update: A Task has finished
            case TaskCompleted event -> handleTaskCompletion(agent, event);
            default -> {
            } // Ignore unknown messages
        }
    }

    /**
     * The core FSM transition logic.
     * Decides what to do next based on which task just finished.
     */
    private void handleTaskCompletion(NerrusAgent agent, TaskCompleted event) {
        if (event.error().isPresent()) {
            fail("Task failed unexpectedly: " + event.error().get().getMessage());
            return;
        }
        // Identify which task finished based on our current state
        switch (state) {
            case VERIFYING_INVENTORY:
                // We just finished counting. Do we have enough?
                if (currentCount >= params.quantity()) {
                    complete();
                } else {
                    // Not enough. Start gathering.
                    this.state = State.GATHERING_BATCH;
                    // Dispatch the monolithic task (treated as a black box for now)
                    // It will run its script and eventually fire TaskCompleted
                    Task gatherTask = new GatherBlockTask(materials, null, 48, attemptedLocations);
                    agent.setCurrentTask(gatherTask);
                }
                break;
            case GATHERING_BATCH:
                // We just finished a gather attempt (successful or not).
                // Verify inventory to confirm progress and decide next step.
                this.state = State.VERIFYING_INVENTORY;
                dispatchInventoryCheck(agent);
                break;
            default:
                // Should not happen if state is managed correctly
                break;
        }
    }

    /**
     * Wraps the CountItemsSkill in an anonymous Task to fit the TaskCompleted lifecycle.
     */
    private void dispatchInventoryCheck(NerrusAgent agent) {
        Task checkTask = new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
                return new CountItemsSkill(materials).execute(agent, token)
                        .thenAcceptAsync(counts -> {
                            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
                            // Post data back to Goal
                            agent.postMessage(token, new InventoryCountResult(total));
                        }, IonNerrus.getInstance().getMainThreadExecutor());
            }
        };
        agent.setCurrentTask(checkTask);
    }

    /**
     * Handles intermediate feedback from the GatherBlockTask.
     * Even though the task is a "black box", it sends these messages to inform us of specific failures.
     */
    private void handleGatherFeedback(GatherAttemptResult result) {
        switch (result.status()) {
            case SUCCESS:
                // Clear attempted locations so we can retry spots from new angles if needed
                this.attemptedLocations.clear();
                break;
            case NO_BLOCKS_IN_RANGE:
                fail("I can't find any more " + params.groupName() + " nearby.");
                break;
            case NO_REACHABLE_BLOCKS_IN_RANGE:
                fail("I can see " + params.groupName() + ", but I can't reach them.");
                break;
            case FAILED_TO_COLLECT:
                // Transient failure. The TaskCompleted event will trigger a re-verify and retry.
                logger.warning("Gather attempt failed, retrying loop.");
                break;
        }
    }

    private void complete() {
        this.state = State.COMPLETED;
    }

    private void fail(String reason) {
        this.failureReason = reason;
        this.state = State.FAILED;
    }

    @Override
    public boolean isFinished() {
        return state == State.COMPLETED || state == State.FAILED;
    }

    @Override
    public void stop(NerrusAgent agent) {
        if (!isFinished()) {
            this.state = State.FAILED;
            this.failureReason = "Goal was stopped manually.";
        }
        this.attemptedLocations.clear();
    }

    @Override
    public GoalResult getFinalResult() {
        if (state == State.COMPLETED) {
            return new GoalResult.Success("Successfully gathered " + currentCount + " " + params.groupName() + ".");
        } else {
            String msg = failureReason != null ? failureReason : "Failed to gather blocks.";
            return new GoalResult.Failure(msg + " (Got " + currentCount + "/" + params.quantity() + ")");
        }
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "GATHER_BLOCK",
                    "Navigates to and gathers a specified quantity of a block type from a predefined group.",
                    GatherBlockParameters.class,
                    (schema, agent) -> {
                        String validGroups = String.join(", ", blockTagManager.getRegisteredGroupNames());
                        ObjectNode properties = (ObjectNode) schema.get("properties");
                        if (properties != null) {
                            ObjectNode groupNameProp = (ObjectNode) properties.get("groupName");
                            if (groupNameProp != null) {
                                String currentDesc = groupNameProp.get("description").asText();
                                groupNameProp.put("description", currentDesc + " Available groups: " + validGroups);
                            }
                        }
                        return schema;
                    });
        }
    }
}