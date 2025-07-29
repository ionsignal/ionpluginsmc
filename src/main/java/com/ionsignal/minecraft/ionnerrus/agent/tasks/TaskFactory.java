package com.ionsignal.minecraft.ionnerrus.agent.tasks;

import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.FindBiomeTask;
// import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.GoToLocationTask;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.impl.GatherBlockTask;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Biome;

import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TaskFactory {
    private final Logger logger;

    public TaskFactory(Logger logger) {
        this.logger = logger;
    }

    @SuppressWarnings("unchecked")
    public Task createTask(String taskName, Map<String, Object> parameters) {
        try {
            switch (taskName.toUpperCase()) {
                case "FIND_BIOME":
                    Set<Biome> biomes = (Set<Biome>) parameters.get("biomes");
                    int findRadius = (int) parameters.getOrDefault("radius", 1000);
                    return new FindBiomeTask(biomes, findRadius);

                // // Currently disabled as this seems a little over powered
                // case "FIND_DENSE_BLOCK_AREA":
                //     Set<Material> areaMaterials = (Set<Material>) parameters.get("materials");
                //     int areaRadius = (int) parameters.getOrDefault("radius", 150);
                //     return new FindDenseBlockAreaTask(areaMaterials, areaRadius);

                // case "GOTO_LOCATION":
                //     String key = (String) parameters.getOrDefault("locationBlackboardKey", "targetLocation");
                //     return new GoToLocationTask(key);

                case "GATHER_BLOCKS":
                    Set<Material> materials = (Set<Material>) parameters.get("materials");
                    int gatherRadius = (int) parameters.getOrDefault("radius", 50);
                    Set<Location> attemptedLocations = (Set<Location>) parameters.get("attemptedLocations");
                    return new GatherBlockTask(materials, gatherRadius, attemptedLocations);

                default:
                    logger.warning("Unknown task name: " + taskName);
                    return null;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create task '" + taskName + "' with parameters: " + parameters, e);
            return null;
        }
    }
}