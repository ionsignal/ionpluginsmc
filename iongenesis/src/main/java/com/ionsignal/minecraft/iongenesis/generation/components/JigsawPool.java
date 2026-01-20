package com.ionsignal.minecraft.iongenesis.generation.components;

import com.dfsek.terra.api.Platform;

import com.ionsignal.minecraft.iongenesis.config.JigsawPoolTemplate;
import com.ionsignal.minecraft.iongenesis.generation.tracking.UsageConstraints;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

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
                    element.getStructure(),
                    element.getWeight(),
                    element.getMinCount(),
                    element.getMaxCount(),
                    element.getMetadata());
            elements.add(weighted);
            weight += element.getWeight();
        }
        this.totalWeight = weight;
    }

    /**
     * Selects a random structure ID from this pool based on weights.
     */
    public String selectRandomElement(RandomGenerator random) {
        if (elements.isEmpty() || totalWeight == 0) {
            return null;
        }
        int target = random.nextInt(totalWeight);
        int current = 0;
        for (WeightedElement element : elements) {
            current += element.weight;
            if (current > target) {
                return element.structureId; // Returns Registry ID
            }
        }
        return elements.get(elements.size() - 1).structureId;
    }

    public String selectRandomElementWithExclusions(RandomGenerator random, Set<String> excludedFiles) {
        if (elements.isEmpty() || totalWeight == 0) {
            return null;
        }
        int availableWeight = elements.stream()
                .filter(e -> !excludedFiles.contains(e.structureId))
                .mapToInt(e -> e.weight)
                .sum();
        if (availableWeight == 0) {
            return null;
        }
        int target = random.nextInt(availableWeight);
        int current = 0;
        for (WeightedElement element : elements) {
            if (excludedFiles.contains(element.structureId)) {
                continue;
            }
            current += element.weight;
            if (current > target) {
                return element.structureId;
            }
        }
        return elements.stream()
                .filter(e -> !excludedFiles.contains(e.structureId))
                .findFirst()
                .map(e -> e.structureId)
                .orElse(null);
    }

    public List<WeightedElement> getElements() {
        return new ArrayList<>(elements);
    }

    public int getTotalWeight() {
        return totalWeight;
    }

    public String getId() {
        return id;
    }

    public String getFallbackPool() {
        return fallbackPool;
    }

    public List<UsageConstraints> getAllConstraints() {
        return elements.stream()
                .map(e -> new UsageConstraints(id, e.structureId, e.minCount, e.maxCount))
                .toList();
    }

    public static class WeightedElement {
        final String structureId;
        final int weight;
        final int minCount;
        final int maxCount;
        final int verticalDelta;
        final String role;

        WeightedElement(String structureId, int weight, int minCount, int maxCount, JigsawPoolTemplate.MetadataTemplate metadata) {
            this.structureId = structureId;
            this.weight = weight;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.verticalDelta = metadata.getVerticalDelta();
            this.role = metadata.getRole();
        }

        public String getStructureId() {
            return structureId;
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

        public int getVerticalDelta() {
            return verticalDelta;
        }

        /**
         * Checks if this element is explicitly marked as a terminator.
         * Used for terrain adaptation fallback strategies.
         *
         * @return true if metadata role is "terminator"
         */
        public boolean isTerminator() {
            return "terminator".equalsIgnoreCase(role);
        }
    }
}