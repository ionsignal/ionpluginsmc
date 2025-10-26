package com.ionsignal.minecraft.ionnerrus.terra.config;

import com.dfsek.tectonic.api.config.template.annotations.Default; // ADDED: Import for default values
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.terra.api.config.AbstractableTemplate;
import com.dfsek.terra.api.config.meta.Meta;

public class JigsawStructureTemplate implements AbstractableTemplate {
	@Value("id")
	private @Meta String id;

	@Value("file")
	private @Meta String file;

	// ADDED: Starting pool reference for jigsaw generation
	@Value("start-pool")
	@Default
	private @Meta String startPool = "minecraft:empty";

	// ADDED: Maximum generation depth (number of connections to follow)
	@Value("max-depth")
	@Default
	private @Meta int maxDepth = 7;

	// ADDED: Maximum radius from starting point
	@Value("max-distance")
	@Default
	private @Meta int maxDistance = 80;

	// ADDED: Whether to adapt to terrain height
	@Value("terrain-adaptation")
	@Default
	private @Meta String terrainAdaptation = "none";

	@Override
	public String getID() {
		return id;
	}

	public String getFile() {
		return file;
	}

	// ADDED: Getter methods for new fields
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
}