package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GetBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.GatherBlockTask.GatherResult;

import org.bukkit.Location;
import org.bukkit.Material;
// import org.bukkit.block.Biome;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class GetBlockGoal implements Goal {
    private enum State {
        CHECKING_INVENTORY, GATHERING, SEARCHING_FOR_DENSE_AREA, MOVING_TO_DENSE_AREA, COMPLETED, FAILED
    }

    private final Logger logger;
    private final TaskFactory taskFactory;
    private final Set<Material> materials;
    private final GetBlockParameters params;
    private final Set<Location> attemptedLocations = new HashSet<>();

    private State state = State.CHECKING_INVENTORY;
    private int gatheredCount = 0;

    public GetBlockGoal(TaskFactory taskFactory, Set<Material> materials, GetBlockParameters params) {
        this.taskFactory = taskFactory;
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
    @SuppressWarnings("incomplete-switch")
    public void process(NerrusAgent agent) {
        updateStateFromTaskResults(agent);
        if (isFinished()) {
            switch (state) {
                case FAILED:
                    agent.speak("I tried, but I couldn't get all the blocks.");
                    logger.warning("GetBlockGoal has failed.");
                    break;
                case COMPLETED:
                    agent.speak("I've gathered all the blocks!");
                    logger.info("GetBlockGoal has completed successfully.");
                    break;
            }
            return;
        }
        Task nextTask = null;
        switch (state) {
            case CHECKING_INVENTORY:
                logger.info("GetBlockGoal: Checking inventory count.");
                nextTask = createUpdateCountTask();
                break;
            case GATHERING:
                logger.info("GetBlockGoal: Attempting to gather one block.");
                nextTask = createGatherOneBlockTask();
                break;

            // NEEDS TO BE UPDATED TO SUPPORT LOOKAT BLOCK
            // case MOVING_TO_DENSE_AREA:/
            // logger.info("GetBlockGoal: Moving to the new area.");
            // nextTask = taskFactory.createTask("GOTO_LOCATION", Map.of());
            // this.state = State.GATHERING_IN_DENSE_AREA; // Optimistically transition
            // break;

            // NEEDS TO BE UPDATED TO SUPPORT CLOSEST STANDING BLOCK
            // case SEARCHING_FOR_DENSE_AREA:
            // agent.speak("Can't find any nearby. I'll look for a better spot.");
            // logger.info("GetBlockGoal: Searching for a dense area of blocks.");
            // nextTask = createFindDenseAreaTask(150);
            // this.state = State.MOVING_TO_DENSE_AREA; // Optimistically transition
            // break;

            default:
                logger.info("GetBlockGoal: Unhandled state <" + state + "> please check.");
                break;
        }
        if (nextTask != null) {
            agent.setCurrentTask(nextTask);
        }
    }

    private void updateStateFromTaskResults(NerrusAgent agent) {
        // Did an UpdateInventoryCountTask just finish?
        agent.getBlackboard().get(BlackboardKeys.GATHER_CURRENT_COUNT, Integer.class).ifPresent(count -> {
            this.gatheredCount = count;
            agent.getBlackboard().remove(BlackboardKeys.GATHER_CURRENT_COUNT);
            if (gatheredCount >= params.quantity()) {
                this.state = State.COMPLETED;
            } else {
                this.state = State.GATHERING;
            }
        });
        // Did a GatherBlocksTask just finish?
        agent.getBlackboard().getEnum(BlackboardKeys.GATHER_BLOCK_RESULT, GatherResult.class).ifPresent(result -> {
            agent.getBlackboard().remove(BlackboardKeys.GATHER_BLOCK_RESULT);
            switch (result) {
                case SUCCESS:
                    // CHANGE: Clear the attempted locations list after a successful gather.
                    // This is critical. Since the agent has moved, the reasons for previous failures
                    // (e.g., a block being unreachable) may no longer be valid from the new position.
                    // This allows the agent to re-evaluate its surroundings with a clean slate.
                    this.attemptedLocations.clear();
                    // After a successful gather, we must re-check the inventory to confirm.
                    this.state = State.CHECKING_INVENTORY;
                    // DEBUG: Instead of re-checking inventory, just increment our count.
                    // this.gatheredCount++;
                    // logger.info("DEBUG: Gathered one block, new count is " + this.gatheredCount);
                    // if (this.gatheredCount >= params.quantity()) {
                    // this.state = State.COMPLETED;
                    // } else {
                    // // If not done, go gather another one.
                    // this.state = State.GATHERING;
                    // }
                    break;
                case NO_BLOCKS_IN_RANGE:
                case NO_REACHABLE_BLOCKS_IN_RANGE:
                    // If we can't find any blocks at all, or reach them, then we have to fail.
                    this.state = State.FAILED;
                    break;
                // It just means we should try gathering again from a different block.
                case FAILED_TO_COLLECT:
                    logger.info("GetBlockGoal: A single gather attempt failed. Retrying...");
                    this.state = State.GATHERING;
                    break;
            }
        });
    }

    private Task createGatherOneBlockTask() {
        Map<String, Object> params = new HashMap<>();
        params.put("materials", materials);
        params.put("attemptedLocations", this.attemptedLocations);
        return taskFactory.createTask("GATHER_BLOCKS", params);
    }

    private Task createUpdateCountTask() {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                return new CountItemsSkill(materials).execute(agent)
                        .thenAccept(count -> agent.getBlackboard().put(BlackboardKeys.GATHER_CURRENT_COUNT, count))
                        .thenApply(v -> null); // Convert CompletableFuture<Void> to CompletableFuture<Void>
            }

            @Override
            public void cancel() {
                /* No-op */
            }
        };
    }

    // private Task createFindDenseAreaTask(int radius) {
    // // Using find biome for now
    // Map<String, Object> params = new HashMap<>();
    // Set<Biome> FOREST_BIOMES = Set.of(
    // Biome.FOREST, Biome.FLOWER_FOREST, Biome.BIRCH_FOREST, Biome.DARK_FOREST,
    // Biome.OLD_GROWTH_BIRCH_FOREST, Biome.TAIGA, Biome.OLD_GROWTH_PINE_TAIGA,
    // Biome.OLD_GROWTH_SPRUCE_TAIGA, Biome.JUNGLE, Biome.SPARSE_JUNGLE,
    // Biome.BAMBOO_JUNGLE, Biome.WINDSWEPT_FOREST);
    // params.put("biomes", FOREST_BIOMES);
    // params.put("radius", 100);
    // // params.put("materials", materials);
    // // params.put("radius", radius);
    // // return taskFactory.createTask("FIND_DENSE_BLOCK_AREA", params);
    // return taskFactory.createTask("FIND_BIOME", params);
    // }

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
            return new GoalResult(GoalResult.Status.SUCCESS, message);
        } else {
            String message = "Failed to gather the required " + params.quantity() + " " + params.groupName() + ". Only found "
                    + gatheredCount + ".";
            return new GoalResult(GoalResult.Status.FAILURE, message);
        }
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "GET_BLOCKS",
                    "Navigates to and gathers a specified quantity of a block type from a predefined group.",
                    GetBlockParameters.class,
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