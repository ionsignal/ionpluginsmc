package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.dfsek.terra.api.Platform;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.generation.tracking.UsageConstraints;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Runtime representation of a jigsaw structure pool.
 * Handles weighted random selection of structure pieces.
 */
public class JigsawPool {
	private final String id;
	private final String fallbackPool;
	private final List<WeightedElement> elements;
	private final int totalWeight;

	public JigsawPool(JigsawPoolTemplate template, Platform platform) {
		this.id = template.getID();
		this.fallbackPool = template.getFallback();
		this.elements = new ArrayList<>();
		int weight = 0;
		for (JigsawPoolTemplate.PoolElement element : template.getElements()) {
			WeightedElement weighted = new WeightedElement(
					element.getFile(),
					element.getWeight(),
					element.getMinCount(),
					element.getMaxCount());
			elements.add(weighted);
			weight += element.getWeight();
		}
		this.totalWeight = weight;
	}

	/**
	 * Selects a random structure file from this pool based on weights.
	 * 
	 * @param random
	 *            Random generator to use
	 * @return Selected structure file path, or null if pool is empty
	 */
	public String selectRandomElement(Random random) {
		if (elements.isEmpty() || totalWeight == 0) {
			return null;
		}
		int target = random.nextInt(totalWeight);
		int current = 0;
		for (WeightedElement element : elements) {
			current += element.weight;
			if (current > target) {
				return element.file;
			}
		}
		// Fallback to last element (shouldn't happen)
		return elements.get(elements.size() - 1).file;
	}

	/**
	 * Selects a random element while respecting maximum count constraints.
	 * This method excludes files that have reached their maximum count.
	 * 
	 * USAGE: Called by JigsawGenerator.selectCandidateFilesRespectingMaxCounts()
	 * 
	 * ALGORITHM:
	 * 1. Calculate total weight of non-excluded elements
	 * 2. If all elements excluded, return null
	 * 3. Generate random number in range [0, availableWeight)
	 * 4. Walk through non-excluded elements until cumulative weight exceeds target
	 * 5. Return the selected element's file path
	 * 
	 * @param random
	 *            Random generator (must be same instance used throughout generation for determinism)
	 * @param excludedFiles
	 *            Set of file paths that have reached their max count
	 * @return Selected file path, or null if no valid options remain
	 */
	public String selectRandomElementWithExclusions(Random random, Set<String> excludedFiles) {
		// Edge case: pool is empty or has no weight
		if (elements.isEmpty() || totalWeight == 0) {
			return null;
		}
		// Calculate available weight (sum of weights for non-excluded elements)
		int availableWeight = elements.stream()
				.filter(e -> !excludedFiles.contains(e.file))
				.mapToInt(e -> e.weight)
				.sum();
		// Edge case: all elements have been excluded
		if (availableWeight == 0) {
			return null;
		}
		// Generate target value in range [0, availableWeight)
		// Walk through elements, accumulating weight for non-excluded items
		int target = random.nextInt(availableWeight);
		int current = 0;
		for (WeightedElement element : elements) {
			// Skip excluded elements
			if (excludedFiles.contains(element.file)) {
				continue;
			}
			current += element.weight;
			if (current > target) {
				return element.file;
			}
		}
		// Fallback: should never reach here due to math above, but provide safety
		// Return the first non-excluded element if we somehow miss the selection
		return elements.stream()
				.filter(e -> !excludedFiles.contains(e.file))
				.findFirst()
				.map(e -> e.file)
				.orElse(null);
	}

	/**
	 * Gets all elements in this pool for external usage tracking.
	 */
	public List<WeightedElement> getElements() {
		return new ArrayList<>(elements);
	}

	/**
	 * Gets total weight of all elements.
	 */
	public int getTotalWeight() {
		return totalWeight;
	}

	/**
	 * Gets the pool ID.
	 */
	public String getId() {
		return id;
	}

	/**
	 * Gets the fallback pool ID.
	 */
	public String getFallbackPool() {
		return fallbackPool;
	}

	/**
	 * Gets all usage constraints defined in this pool's elements.
	 * Each element contributes a constraint for its min/max counts.
	 * 
	 * USAGE: Called by PoolRegistry.getAllConstraints() during generator initialization
	 * 
	 * @return List of UsageConstraints, one per element in this pool
	 */
	public List<UsageConstraints> getAllConstraints() {
		return elements.stream()
				.map(e -> new UsageConstraints(id, e.file, e.minCount, e.maxCount))
				.toList();
	}

	/**
	 * Internal representation of a weighted pool element.
	 */
	public static class WeightedElement {
		final String file;
		final int weight;
		final int minCount;
		final int maxCount;

		WeightedElement(String file, int weight, int minCount, int maxCount) {
			this.file = file;
			this.weight = weight;
			this.minCount = minCount;
			this.maxCount = maxCount;
		}

		public String getFile() {
			return file;
		}

		public int getWeight() {
			return weight;
		}

		public int getMinCount() {
			return minCount;
		}

		public int getMaxCount() {
			return maxCount;
		}
	}
}