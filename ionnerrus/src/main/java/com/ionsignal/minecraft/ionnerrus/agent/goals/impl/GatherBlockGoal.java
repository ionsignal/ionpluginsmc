package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GatherBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
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
    private enum State {
        CHECKING_INVENTORY, GATHERING, SEARCHING_FOR_DENSE_AREA, MOVING_TO_DENSE_AREA, COMPLETED, FAILED
    }

    // This enum defines the outcome space of the goal's gather operation
    public enum GatherResult {
        SUCCESS, NO_BLOCKS_IN_RANGE, NO_REACHABLE_BLOCKS_IN_RANGE, FAILED_TO_COLLECT
    }

    /**
     * Message sent by the inventory counting task after it completes.
     * 
     * @param count
     *            The total number of items of the target material(s) currently in the inventory.
     */
    public static record InventoryCountResult(int count) {
    }

    /**
     * Message sent by GatherBlockTask after each gather attempt.
     * 
     * @param status
     *            The outcome of the gather attempt.
     */
    public static record GatherAttemptResult(GatherResult status) {
    }

    private final Logger logger;
    private final Set<Material> materials;
    private final GatherBlockParameters params;
    private final Object contextToken = new Object();
    private final Set<Location> attemptedLocations = new HashSet<>();
    private State state = State.CHECKING_INVENTORY;
    private int gatheredCount = 0;

    public GatherBlockGoal(Set<Material> materials, GatherBlockParameters params) {
        this.materials = materials;
        this.params = params;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public void start(NerrusAgent agent) {
        this.attemptedLocations.clear();
        agent.speak("Okay, I'll get " + params.quantity() + " " + params.groupName() + ".");
    }

    @Override
    public void onMessage(NerrusAgent agent, Object message) {
        switch (message) {
            case InventoryCountResult result -> {
                // Update our count from the inventory check
                this.gatheredCount = result.count();
                if (gatheredCount >= params.quantity()) {
                    this.state = State.COMPLETED;
                } else {
                    this.state = State.GATHERING;
                }
                logger.info("GatherGoal: Inventory count updated to " + gatheredCount + "/" + params.quantity());
            }
            case GatherAttemptResult result -> {
                // Handle the result of a gather attempt
                switch (result.status()) {
                    case SUCCESS:
                        // IMPORTANT: Clear the attempted locations list after a successful gather. Since the agent has
                        // moved, the reasons for previous failures (e.g., a block being unreachable) may no longer be valid
                        // from the new position.
                        this.attemptedLocations.clear();
                        // After success, re-check inventory to confirm the block was collected
                        this.state = State.CHECKING_INVENTORY;
                        break;
                    case NO_BLOCKS_IN_RANGE:
                    case NO_REACHABLE_BLOCKS_IN_RANGE:
                        // Terminal failure states - no recovery possible
                        this.state = State.FAILED;
                        break;
                    case FAILED_TO_COLLECT:
                        // Transient failure - block exists but couldn't be collected this time Retry from the current state
                        logger.info("GatherGoal: A single gather attempt failed. Retrying...");
                        this.state = State.GATHERING;
                        break;
                }
            }
            default -> logger.warning("GatherGoal received unknown message type: " + message.getClass().getName());
        }
    }

    @Override
    @SuppressWarnings("incomplete-switch")
    public void process(NerrusAgent agent) {
        if (isFinished()) {
            switch (state) {
                case FAILED:
                    agent.speak("I tried, but I couldn't get all the blocks.");
                    logger.warning("GatherGoal has failed.");
                    break;
                case COMPLETED:
                    agent.speak("I've gathered all the blocks!");
                    logger.info("GatherGoal has completed successfully.");
                    break;
            }
            return;
        }
        Task nextTask = null;
        switch (state) {
            case CHECKING_INVENTORY:
                logger.info("GatherGoal: Checking inventory count.");
                nextTask = createUpdateCountTask();
                break;
            case GATHERING:
                logger.info("GatherGoal: Attempting to gather one block.");
                nextTask = createGatherOneBlockTask();
                break;
            default:
                logger.info("GatherGoal: Unhandled state <" + state + "> please check.");
                break;
        }
        if (nextTask != null) {
            agent.setCurrentTask(nextTask);
        }
    }

    private Task createGatherOneBlockTask() {
        // TODO: In a future update, calculate drops based on 'materials'
        return new GatherBlockTask(materials, null, 50, attemptedLocations, contextToken);
    }

    private Task createUpdateCountTask() {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                return new CountItemsSkill(materials).execute(agent)
                        .thenAcceptAsync(counts -> {
                            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
                            agent.postMessage(contextToken, new InventoryCountResult(total));
                        }, IonNerrus.getInstance().getMainThreadExecutor());
            }

            @Override
            public void cancel() {
                /* No-op */
            }
        };
    }

    @Override
    public boolean isFinished() {
        return state == State.COMPLETED || state == State.FAILED;
    }

    @Override
    public void stop(NerrusAgent agent) {
        if (state != State.COMPLETED) {
            this.state = State.FAILED;
        }
        this.attemptedLocations.clear();
        logger.info("Goal stopped: 'get block' goal");
    }

    @Override
    public GoalResult getFinalResult() {
        if (state == State.COMPLETED) {
            String message = "Successfully gathered " + gatheredCount + " " + params.groupName() + ".";
            return new GoalResult.Success(message);
        } else {
            String message = "Failed to gather the required " + params.quantity() + " " + params.groupName() + ". Only found "
                    + gatheredCount + ".";
            return new GoalResult.Failure(message);
        }
    }

    @Override
    public Object getContextToken() {
        return contextToken;
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