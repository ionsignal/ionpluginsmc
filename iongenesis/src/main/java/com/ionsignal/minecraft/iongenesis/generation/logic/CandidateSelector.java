package com.ionsignal.minecraft.iongenesis.generation.logic;

import com.ionsignal.minecraft.iongenesis.generation.JigsawPool;
import com.ionsignal.minecraft.iongenesis.generation.tracking.PoolUsageTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Logic component responsible for selecting which structure piece to try next.
 * Handles pool weights and maximum count constraints.
 */
public class CandidateSelector {
    private final RandomGenerator random;
    private final PoolUsageTracker usageTracker;

    public CandidateSelector(RandomGenerator random, PoolUsageTracker usageTracker) {
        this.random = random;
        this.usageTracker = usageTracker;
    }

    /**
     * Selects a list of candidate structure IDs from a pool, respecting max usage counts.
     * 
     * @param pool
     *            The pool to select from.
     * @param count
     *            The desired number of candidates to try.
     * @return A list of structure IDs (Registry Keys).
     */
    public List<String> selectCandidates(JigsawPool pool, int count) {
        List<String> candidates = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        int attempts = 0;
        int maxAttempts = count * 3;
        while (candidates.size() < count && attempts++ < maxAttempts) {
            Set<String> excludedIds = new HashSet<>();
            // Filter out elements that have reached their max count
            for (JigsawPool.WeightedElement element : pool.getElements()) {
                int currentCount = usageTracker.getCount(pool.getId(), element.getStructureId());
                int pendingCount = (int) candidates.stream().filter(c -> c.equals(element.getStructureId())).count();

                if (currentCount + pendingCount >= element.getMaxCount()) {
                    excludedIds.add(element.getStructureId());
                }
            }
            // If everything is excluded, we can't select anything
            if (excludedIds.size() == pool.getElements().size()) {
                break;
            }
            String id = pool.selectRandomElementWithExclusions(random, excludedIds);
            if (id == null) {
                break;
            }
            if (!selected.contains(id)) {
                candidates.add(id);
                selected.add(id);
            }
        }
        return candidates;
    }
}