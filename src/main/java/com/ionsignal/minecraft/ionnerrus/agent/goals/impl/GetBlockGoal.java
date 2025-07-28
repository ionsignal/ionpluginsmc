package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.BlackboardKeys;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.GatherBlocksTask.GatherResult;

import org.bukkit.Material;
// import org.bukkit.block.Biome;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GetBlockGoal implements Goal {

    private enum State {
        CHECKING_INVENTORY, GATHERING, SEARCHING_FOR_DENSE_AREA, MOVING_TO_DENSE_AREA, COMPLETED, FAILED
    }

    private final TaskFactory taskFactory;
    private final Set<Material> materials;
    private final int requiredCount;
    private final Logger logger;

    private State state = State.CHECKING_INVENTORY;
    private int gatheredCount = 0;

    private static final Set<Material> WOOD_LOGS = Arrays.stream(Material.values())
            .filter(m -> m.name().endsWith("_LOG"))
            .collect(Collectors.toSet());

    public GetBlockGoal(TaskFactory taskFactory, int count) {
        this.taskFactory = taskFactory;
        this.materials = WOOD_LOGS;
        this.requiredCount = count;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public void start(NerrusAgent agent) {
        agent.speak(
                "Okay, I'll get " + requiredCount + " " + materials.iterator().next().toString().toLowerCase().replace('_', ' ') + "s.");
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
                nextTask = createGatherOneBlockTask(50);
                break;

            // NEEDS TO BE UPDATED TO SUPPORT LOOKAT BLOCK
            // case MOVING_TO_DENSE_AREA:
            //     logger.info("GetBlockGoal: Moving to the new area.");
            //     nextTask = taskFactory.createTask("GOTO_LOCATION", Map.of());
            //     this.state = State.GATHERING_IN_DENSE_AREA; // Optimistically transition
            //     break;

            // NEEDS TO BE UPDATED TO SUPPORT CLOSEST STANDING BLOCK
            // case SEARCHING_FOR_DENSE_AREA:
            //     agent.speak("Can't find any nearby. I'll look for a better spot.");
            //     logger.info("GetBlockGoal: Searching for a dense area of blocks.");
            //     nextTask = createFindDenseAreaTask(150);
            //     this.state = State.MOVING_TO_DENSE_AREA; // Optimistically transition
            //     break;

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
            if (gatheredCount >= requiredCount) {
                this.state = State.COMPLETED;
            } else {
                this.state = State.GATHERING;
            }
        });
        // Did a GatherBlocksTask just finish?
        agent.getBlackboard().getEnum(BlackboardKeys.GATHER_BLOCKS_RESULT, GatherResult.class).ifPresent(result -> {
            agent.getBlackboard().remove(BlackboardKeys.GATHER_BLOCKS_RESULT);
            switch (result) {
                case SUCCESS:
                    // After a successful gather, we must re-check the inventory to confirm.
                    this.state = State.CHECKING_INVENTORY;
                    break;
                case NO_BLOCKS_FOUND:
                case FAILED_TO_COLLECT:
                    this.state = State.FAILED;
                    break;
            }
        });
    }

    private Task createGatherOneBlockTask(int radius) {
        Map<String, Object> params = new HashMap<>();
        params.put("materials", materials);
        params.put("radius", radius);
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
        if (state != State.COMPLETED) {
            this.state = State.FAILED;
        }
        logger.info("Goal stopped: 'get block' goal");
    }
}