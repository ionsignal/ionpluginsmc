package com.ionsignal.minecraft.ionnerrus.terra.generation.debug;

import org.bukkit.scheduler.BukkitRunnable;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Main thread task that updates debug visualizations.
 * Runs every tick while debug sessions are active.
 */
public class DebugRenderTask extends BukkitRunnable {
	private static final Logger LOGGER = Logger.getLogger(DebugRenderTask.class.getName());

	private final Map<?, DebugContext> activeContexts;

	public DebugRenderTask(Map<?, DebugContext> activeContexts) {
		this.activeContexts = activeContexts;
	}

	@Override
	public void run() {
		if (activeContexts.isEmpty()) {
			return;
		}
		// Safely iterate over the live ConcurrentHashMap
		for (DebugContext context : activeContexts.values()) {
			if (context != null && context.isRunning() && context.isVisualizationDirty()) {
				try {
					DebugVisualizer.updateVisualization(context);
				} catch (Exception e) {
					LOGGER.warning("Failed to update debug visualization: " + e.getMessage());
				}
			}
		}
	}
}