// package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

// import com.ionsignal.minecraft.ionnerrus.IonNerrus;
// import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
// import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
// import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
// import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;
// import org.bukkit.Material;
// import org.bukkit.block.Biome;

// import java.util.Map;
// import java.util.Set;
// import java.util.Queue;
// import java.util.LinkedList;
// import java.util.Arrays;
// import java.util.HashMap;
// import java.util.Collections;
// import java.util.stream.Collectors;
// import java.util.logging.Logger;

// public class GetWoodGoal implements Goal {

//     private static final Set<Biome> FOREST_BIOMES = Set.of(
//             Biome.FOREST, Biome.FLOWER_FOREST, Biome.BIRCH_FOREST, Biome.DARK_FOREST,
//             Biome.OLD_GROWTH_BIRCH_FOREST, Biome.TAIGA, Biome.OLD_GROWTH_PINE_TAIGA,
//             Biome.OLD_GROWTH_SPRUCE_TAIGA, Biome.JUNGLE, Biome.SPARSE_JUNGLE,
//             Biome.BAMBOO_JUNGLE, Biome.WINDSWEPT_FOREST);

//     private static final Set<Material> WOOD_LOGS = Arrays.stream(Material.values())
//             .filter(m -> m.name().endsWith("_LOG"))
//             .collect(Collectors.toSet());

//     private final TaskFactory taskFactory;
//     private final int count;
//     private final Logger logger;
//     private final Queue<Task> tasks = new LinkedList<>();
//     private boolean started = false;
//     private boolean finished = false;

//     public GetWoodGoal(TaskFactory taskFactory, int count) {
//         this.taskFactory = taskFactory;
//         this.count = count;
//         this.logger = IonNerrus.getInstance().getLogger();
//     }

//     @Override
//     public void start(NerrusAgent agent) {
//         agent.speak("Okay, I'll get " + count + " logs.");

//         Map<String, Object> gatherParams = new HashMap<>();
//         gatherParams.put("materials", WOOD_LOGS);
//         gatherParams.put("count", count);
//         gatherParams.put("radius", 50);
//         tasks.add(taskFactory.createTask("GATHER_BLOCKS", gatherParams));

//         Map<String, Object> findBiomeParams = new HashMap<>();
//         findBiomeParams.put("biomes", FOREST_BIOMES);
//         findBiomeParams.put("radius", 100);
//         tasks.add(taskFactory.createTask("FIND_BIOME", findBiomeParams));
//         tasks.add(taskFactory.createTask("GOTO_LOCATION", Collections.emptyMap()));

//         this.started = true;
//     }

//     @Override
//     public void process(NerrusAgent agent) {
//         // This simple goal has a static plan. We just execute the next task in the queue.
//         // A more complex, reactive goal would have logic here to decide the next task based on the agent's blackboard.
//         if (finished) {
//             return;
//         }
//         Task nextTask = tasks.poll();
//         if (nextTask != null) {
//             agent.setCurrentTask(nextTask);
//         } else {
//             // No more tasks, the goal is complete.
//             if (started) { // ensure we don't say this if the goal was empty
//                 logger.info("Goal finished: 'get wood' goal");
//             }
//             this.finished = true;
//         }
//     }

//     @Override
//     public boolean isFinished() {
//         return finished;
//     }

//     @Override
//     public void stop(NerrusAgent agent) {
//         this.finished = true;
//         logger.info("Goal stopped: 'get wood' goal");
//     }
// }