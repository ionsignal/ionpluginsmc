package com.ionsignal.minecraft.ionnerrus.terra.config;

import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.terra.api.config.AbstractableTemplate;
import com.dfsek.terra.api.config.meta.Meta;

public class JigsawStructureTemplate implements AbstractableTemplate {
	@Value("id")
	private @Meta String id;

	@Value("file")
	private @Meta String file;

	@Value("start-pool")
	@Default
	private @Meta String startPool = "minecraft:empty";

	@Value("max-depth")
	@Default
	private @Meta int maxDepth = 7;

	@Value("max-distance")
	@Default
	private @Meta int maxDistance = 80;

	@Value("terrain-adaptation")
	@Default
	private @Meta String terrainAdaptation = "none";

	@Value("enforcement-strategy")
	@Default
	private @Meta String enforcementStrategy = "best_effort";

	@Override
	public String getID() {
		return id;
	}

	public String getFile() {
		return file;
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

	/**
	 * Gets the enforcement strategy for min/max count constraints.
	 * 
	 * @return Enforcement strategy string (strict, best_effort, or flexible)
	 */
	public String getEnforcementStrategy() {
		return enforcementStrategy;
	}
}