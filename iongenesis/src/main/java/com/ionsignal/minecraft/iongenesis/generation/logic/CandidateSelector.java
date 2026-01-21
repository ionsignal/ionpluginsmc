package com.ionsignal.minecraft.iongenesis.generation.logic;

import com.ionsignal.minecraft.iongenesis.generation.components.JigsawPool;
import com.ionsignal.minecraft.iongenesis.generation.tracking.PoolUsageTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * Logic component responsible for selecting which structure piece to try next.
 * Handles pool weights, maximum count constraints, and terrain adaptation.
 */
public class CandidateSelector {
    private static final Logger LOGGER = Logger.getLogger(CandidateSelector.class.getName());

    private final RandomGenerator random;
    private final PoolUsageTracker usageTracker;

    public CandidateSelector(RandomGenerator random, PoolUsageTracker usageTracker) {
        this.random = random;
        this.usageTracker = usageTracker;
    }

    /**
     * Selects a list of candidate structure elements from a pool, respecting max usage counts.
     * Defaults to FLAT terrain trend (no bias).
     *
     */
    public List<JigsawPool.WeightedElement> selectCandidates(JigsawPool pool, int count) {
        return selectCandidates(pool, count, TerrainContext.flat(0));
    }

    /**
     * Selects a list of candidate structure elements from a pool, respecting max usage counts
     * and biasing towards pieces that match the terrain context.
     */
    public List<JigsawPool.WeightedElement> selectCandidates(JigsawPool pool, int count, TerrainContext context) {
        List<JigsawPool.WeightedElement> candidates = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        int attempts = 0;
        // Increased max attempts slightly to account for filtering retries
        int maxAttempts = count * 4;
        // Pre-calculate exclusions based on Context
        Set<String> contextExclusions = new HashSet<>();
        // Obstruction/Cliff Logic:
        // If obstructed or cliff, we MUST use a terminator or specific adapter.
        // We exclude EVERYTHING that isn't a terminator.
        boolean forceTerminators = context.isObstructed() || context.isCliff();
        // Slope Logic:
        // Only apply if NOT forcing terminators
        if (!forceTerminators && context.trend() != TerrainTrend.FLAT) {
            for (JigsawPool.WeightedElement element : pool.getElements()) {
                int delta = element.getVerticalDelta();
                // If Rising, exclude pieces that go down or stay flat
                if (context.trend() == TerrainTrend.RISING && delta <= 0) {
                    contextExclusions.add(element.getStructureId());
                } else if (context.trend() == TerrainTrend.FALLING && delta >= 0) {
                    contextExclusions.add(element.getStructureId());
                }
            }
        }
        // If forcing terminators, populate exclusions with all NON-terminators
        if (forceTerminators) {
            for (JigsawPool.WeightedElement element : pool.getElements()) {
                if (!element.isTerminator()) {
                    contextExclusions.add(element.getStructureId());
                }
            }
            LOGGER.info("[Terrain] Forcing terminators due to Obstruction/Cliff");
        }
        boolean fallbackTriggered = false;
        while (candidates.size() < count && attempts++ < maxAttempts) {
            Set<String> currentExclusions = new HashSet<>();
            // Add Usage Constraints (Max Count)
            for (JigsawPool.WeightedElement element : pool.getElements()) {
                int currentCount = usageTracker.getCount(pool.getId(), element.getStructureId());
                // Count how many of this ID we have already selected in this batch
                int pendingCount = (int) candidates.stream().filter(c -> c.getStructureId().equals(element.getStructureId())).count();
                if (currentCount + pendingCount >= element.getMaxCount()) {
                    currentExclusions.add(element.getStructureId());
                }
            }
            // Add Context Constraints (unless we already fell back)
            if (!fallbackTriggered) {
                currentExclusions.addAll(contextExclusions);
            }
            // Try selection
            JigsawPool.WeightedElement element = pool.selectRandomWeightedElementWithExclusions(random, currentExclusions);
            String id = element != null ? element.getStructureId() : null;
            // If selection failed
            if (id == null) {
                // If we were forcing terminators (Obstruction/Cliff) and failed, we are in trouble.
                // We likely have no terminators left (max count) or none in pool.
                // We should try to fallback to standard selection (maybe a small piece fits?)
                // OR return empty to let the Planner handle the seal.
                if (forceTerminators && !fallbackTriggered) {
                    LOGGER.info("[Terrain] Forced terminator selection failed. Falling back to standard pool.");
                    fallbackTriggered = true;
                    continue;
                }
                // If we were filtering for slope (Rising/Falling) and failed
                if (!forceTerminators && !fallbackTriggered && !contextExclusions.isEmpty()) {
                    // Try terminators first (Cap the slope)
                    Set<String> terminatorOnlyExclusions = new HashSet<>();
                    for (JigsawPool.WeightedElement e : pool.getElements()) {
                        // Exclude non-terminators AND maxed-out pieces
                        int currentCount = usageTracker.getCount(pool.getId(), e.getStructureId());
                        if (!e.isTerminator() || currentCount >= e.getMaxCount()) {
                            terminatorOnlyExclusions.add(e.getStructureId());
                        }
                    }
                    element = pool.selectRandomWeightedElementWithExclusions(random, terminatorOnlyExclusions);
                    id = element != null ? element.getStructureId() : null;

                    if (id != null) {
                        LOGGER.info("[Terrain] Selected Cap '" + id + "' for " + context.trend() + " terrain.");
                    } else {
                        // No caps available, fallback to standard weights
                        LOGGER.info("[Terrain] No caps found for " + context.trend() + ". Falling back.");
                        fallbackTriggered = true;
                        continue;
                    }
                } else {
                    // Truly exhausted
                    break;
                }
            }
            // Add selected Element to candidates
            if (element != null && !selectedIds.contains(id)) {
                candidates.add(element);
                selectedIds.add(id);
            }
        }
        return candidates;
    }
}
