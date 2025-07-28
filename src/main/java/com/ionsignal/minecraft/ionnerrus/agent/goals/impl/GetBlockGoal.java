package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.GatherBlocksTask.GatherResult;

import org.bukkit.Material;
// import org.bukkit.block.Biome;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GetBlockGoal implements Goal {

    private enum State {
        IDLE, GATHERING_LOCALLY, SEARCHING_FOR_DENSE_AREA, MOVING_TO_DENSE_AREA, GATHERING_IN_DENSE_AREA, COMPLETED, FAILED
    }

    private final TaskFactory taskFactory;
    private final Set<Material> materials;
    private final int requiredCount;
    private final Logger logger;

    private State state = State.IDLE;
    private int gatheredCount = 0;

    // TEMPORARY HARDCODED MATERIALS // <-- Add block name classification/tagging system
    private static final Set<Material> WOOD_LOGS = Arrays.stream(Material.values())
            .filter(m -> m.name().endsWith("_LOG"))
            .collect(Collectors.toSet());

    public GetBlockGoal(TaskFactory taskFactory, int count) { // (TaskFactory taskFactory, Set<Material> materials, int count) {
        this.taskFactory = taskFactory;
        this.materials = WOOD_LOGS; // materials; // <-- Add block name classification/tagging system
        this.requiredCount = count;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public void start(NerrusAgent agent) {
        agent.speak(
                "Okay, I'll get " + requiredCount + " " + materials.iterator().next().toString().toLowerCase().replace('_', ' ') + "s.");
        this.state = State.GATHERING_LOCALLY;
    }

    @Override
    @SuppressWarnings("incomplete-switch")
    public void process(NerrusAgent agent) {
        updateStateFromBlackboard(agent);
        if (isFinished()) {
            return;
        }
        Task nextTask = null;
        switch (state) {
            case GATHERING_LOCALLY:
                logger.info("GetBlockGoal: Attempting to gather blocks locally.");
                nextTask = createGatherTask(50);
                break;

            // case SEARCHING_FOR_DENSE_AREA:
            //     agent.speak("Can't find any nearby. I'll look for a better spot.");
            //     logger.info("GetBlockGoal: Searching for a dense area of blocks.");
            //     nextTask = createFindDenseAreaTask(150);
            //     this.state = State.MOVING_TO_DENSE_AREA; // Optimistically transition
            //     break;

            // case MOVING_TO_DENSE_AREA:
            //     logger.info("GetBlockGoal: Moving to the new area.");
            //     nextTask = taskFactory.createTask("GOTO_LOCATION", Map.of());
            //     this.state = State.GATHERING_IN_DENSE_AREA; // Optimistically transition
            //     break;

            // case GATHERING_IN_DENSE_AREA:
            //     agent.speak("Okay, let's try gathering here.");
            //     logger.info("GetBlockGoal: Attempting to gather in the new area.");
            //     nextTask = createGatherTask(50);
            //     break;

            case FAILED:
                agent.speak("I tried, but I couldn't get all the blocks.");
                logger.warning("GetBlockGoal has failed.");
                break;

            case COMPLETED:
                agent.speak("I've gathered all the blocks!");
                logger.info("GetBlockGoal has completed successfully.");
                break;

            case IDLE:
                break;
        }

        if (nextTask != null) {
            agent.setCurrentTask(nextTask);
        }
    }

    private void updateStateFromBlackboard(NerrusAgent agent) {
        gatheredCount = agent.getBlackboard().getInt(BlackboardKeys.GATHERED_COUNT, gatheredCount);
        if (gatheredCount >= requiredCount) {
            this.state = State.COMPLETED;
            return;
        }

        // Check for GatherBlocksTask result
        agent.getBlackboard().getEnum(BlackboardKeys.GATHER_BLOCKS_RESULT, GatherResult.class).ifPresent(result -> {
            if (state == State.GATHERING_LOCALLY) {
                if (result == GatherResult.NO_BLOCKS_FOUND) {
                    // this.state = State.SEARCHING_FOR_DENSE_AREA;
                    // FAIL AND STOP if we can't gather locally
                    this.state = State.FAILED;
                } else if (result == GatherResult.FAILED_TO_COLLECT) {
                    this.state = State.FAILED;
                }
            } else if (state == State.GATHERING_IN_DENSE_AREA) {
                // If it fails even in the new area, we give up.
                this.state = State.FAILED;
            }
            agent.getBlackboard().remove(BlackboardKeys.GATHER_BLOCKS_RESULT);
        });

        // Check for FindDenseBlockAreaTask result
        agent.getBlackboard().get(BlackboardKeys.FIND_AREA_RESULT, Boolean.class).ifPresent(found -> {
            if (!found) { // If the search failed to find any area
                this.state = State.FAILED;
            }
            agent.getBlackboard().remove(BlackboardKeys.FIND_AREA_RESULT);
        });
    }

    private Task createGatherTask(int radius) {
        Map<String, Object> params = new HashMap<>();
        params.put("materials", materials);
        params.put("count", requiredCount - gatheredCount);
        params.put("radius", radius);
        return taskFactory.createTask("GATHER_BLOCKS", params);
    }

    // private Task createFindDenseAreaTask(int radius) {
    //     // Using find biome for now
    //     Map<String, Object> params = new HashMap<>();
    //     Set<Biome> FOREST_BIOMES = Set.of(
    //             Biome.FOREST, Biome.FLOWER_FOREST, Biome.BIRCH_FOREST, Biome.DARK_FOREST,
    //             Biome.OLD_GROWTH_BIRCH_FOREST, Biome.TAIGA, Biome.OLD_GROWTH_PINE_TAIGA,
    //             Biome.OLD_GROWTH_SPRUCE_TAIGA, Biome.JUNGLE, Biome.SPARSE_JUNGLE,
    //             Biome.BAMBOO_JUNGLE, Biome.WINDSWEPT_FOREST);
    //     params.put("biomes", FOREST_BIOMES);
    //     params.put("radius", 100);
    //     // params.put("materials", materials);
    //     // params.put("radius", radius);
    //     // return taskFactory.createTask("FIND_DENSE_BLOCK_AREA", params);
    //     return taskFactory.createTask("FIND_BIOME", params);
    // }

    @Override
    public boolean isFinished() {
        return state == State.COMPLETED || state == State.FAILED;
    }

    @Override
    public void stop(NerrusAgent agent) {
        this.state = State.FAILED;
        logger.info("Goal stopped: 'get block' goal");
    }
}