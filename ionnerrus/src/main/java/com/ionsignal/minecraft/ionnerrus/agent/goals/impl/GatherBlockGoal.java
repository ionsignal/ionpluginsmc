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
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.GatherBlockTask;

import org.bukkit.Location;
import org.bukkit.Material;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public class GatherBlockGoal implements Goal {
    private final static int DEFAULT_SEARCH_RADIUS = 48;

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
    private final Set<Location> attemptedLocations = new HashSet<>();

    // State Data
    private State state = State.STARTING;
    private int currentCount = 0;
    private String failureReason = null;
    private GatherResult lastGatherStatus = GatherResult.SUCCESS;

    public GatherBlockGoal(Set<Material> materials, GatherBlockParameters params) {
        this.materials = materials;
        this.params = params;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public void start(NerrusAgent agent, ExecutionToken token) {
        agent.speak("Okay, I'll get " + params.quantity() + " " + params.groupName() + ".");
        this.attemptedLocations.clear();
        checkInventory(agent, token);
    }

    @Override
    public void process(NerrusAgent agent, ExecutionToken token) {
        // no-op
    }

    @Override
    public void onMessage(NerrusAgent agent, Object message, ExecutionToken token) {
        switch (message) {
            case InventoryCountResult result -> handleInventoryResult(agent, result);
            case GatherAttemptResult result -> {
                this.lastGatherStatus = result.status();
            }
            case TaskCompleted event -> handleTaskCompletion(agent, token, event);
            default -> {
                // no-op
            }
        }
    }

    /**
     * Wraps the CountItemsSkill in an anonymous Task to fit the TaskCompleted lifecycle.
     */
    private void checkInventory(NerrusAgent agent, ExecutionToken token) {
        this.state = State.VERIFYING_INVENTORY;
        // Execute as Skill (Atomic, Fast)
        agent.executeSkill(
                new CountItemsSkill(materials), token, counts -> {
                    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
                    return new InventoryCountResult(total);
                });
    }

    /**
     * Handles the result of the CountItemsSkill.
     * Since this is a Skill, the agent is free, so we can transition immediately.
     */
    private void handleInventoryResult(NerrusAgent agent, InventoryCountResult result) {
        this.currentCount = result.count();
        logger.info(String.format("[%s] Inventory: %d/%d %s", agent.getName(), currentCount, params.quantity(), params.groupName()));
        if (currentCount >= params.quantity()) {
            complete();
        } else {
            // Transition to "gathering" blocks state
            this.state = State.GATHERING_BATCH;
            // Start the heavy Task
            agent.setCurrentTask(new GatherBlockTask(
                    materials,
                    DEFAULT_SEARCH_RADIUS,
                    attemptedLocations));
        }
    }

    /**
     * Handles the completion of the GatherBlockTask.
     * This is where we decide whether to loop or fail based on 'lastGatherStatus'.
     */
    private void handleTaskCompletion(NerrusAgent agent, ExecutionToken token, TaskCompleted event) {
        if (state != State.GATHERING_BATCH) {
            return;
        }
        if (event.error().isPresent()) {
            fail("Task failed: " + event.error().get().getMessage());
            return;
        }
        // Analyze the result of the task that just finished
        switch (lastGatherStatus) {
            case SUCCESS:
                // Loop back to inventory check
                this.attemptedLocations.clear(); // Reset cache on success
            case FAILED_TO_COLLECT: // Transient failure, retry logic handles it
                this.state = State.VERIFYING_INVENTORY;
                checkInventory(agent, token);
                break;
            case NO_REACHABLE_BLOCKS_IN_RANGE:
                fail("I see " + params.groupName() + ", but I can't reach them.");
                break;
            case NO_BLOCKS_IN_RANGE:
                fail("I can't find any more " + params.groupName() + ".");
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