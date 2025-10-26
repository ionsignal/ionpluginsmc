package com.ionsignal.minecraft.ionnerrus.terra.generation;

import com.dfsek.terra.api.Platform;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

	public String getId() {
		return id;
	}

	public String getFallbackPool() {
		return fallbackPool;
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