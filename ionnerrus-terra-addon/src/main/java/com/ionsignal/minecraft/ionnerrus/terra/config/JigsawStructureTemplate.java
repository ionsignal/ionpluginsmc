package com.ionsignal.minecraft.ionnerrus.terra.config;

import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.terra.api.config.AbstractableTemplate;
import com.dfsek.terra.api.config.meta.Meta;

public class JigsawStructureTemplate implements AbstractableTemplate {
    @Value("id")
    private @Meta String id;

    @Value("structure")
    private @Meta String structure;

    @Default
    @Value("start-pool")
    private @Meta String startPool = "minecraft:empty";

    @Default
    @Value("max-depth")
    private @Meta int maxDepth = 7;

    @Default
    @Value("max-distance")
    private @Meta int maxDistance = 80;

    @Default
    @Value("terrain-adaptation")
    private @Meta String terrainAdaptation = "none";

    @Default
    @Value("enforcement-strategy")
    private @Meta String enforcementStrategy = "best_effort";

    @Override
    public String getID() {
        return id;
    }

    public String getStructure() {
        return structure;
    }

    public String getStartPool() {
        return startPool;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int getMaxDistance() {
        return maxDistance;
    }

    public String getTerrainAdaptation() {
        return terrainAdaptation;
    }

    public String getEnforcementStrategy() {
        return enforcementStrategy;
    }
}