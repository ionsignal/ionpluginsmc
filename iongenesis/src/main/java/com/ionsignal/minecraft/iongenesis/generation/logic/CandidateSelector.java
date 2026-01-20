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
     * Selects a list of candidate structure IDs from a pool, respecting max usage counts.
     * Defaults to FLAT terrain trend (no bias).
     *
     * @param pool
     *            The pool to select from.
     * @param count
     *            The desired number of candidates to try.
     * @return A list of structure IDs (Registry Keys).
     */
    public List<String> selectCandidates(JigsawPool pool, int count) {
        return selectCandidates(pool, count, TerrainTrend.FLAT);
    }

    /**
     * Selects a list of candidate structure IDs from a pool, respecting max usage counts
     * and biasing towards pieces that match the terrain trend.
     *
     * @param pool
     *            The pool to select from.
     * @param count
     *            The desired number of candidates to try.
     * @param trend
     *            The detected terrain trend ahead of the connection.
     * @return A list of structure IDs (Registry Keys).
     */
    public List<String> selectCandidates(JigsawPool pool, int count, TerrainTrend trend) {
        List<String> candidates = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        int attempts = 0;
        // Increased max attempts slightly to account for filtering retries
        int maxAttempts = count * 4;
        // Pre-calculate trend-based exclusions
        // We identify which pieces definitely DO NOT fit the current terrain trend.
        Set<String> trendExclusions = new HashSet<>();
        if (trend != TerrainTrend.FLAT) {
            for (JigsawPool.WeightedElement element : pool.getElements()) {
                int delta = element.getVerticalDelta();
                // If Rising, exclude pieces that go down or stay flat
                // Rising needs > 0. Falling needs < 0
                if (trend == TerrainTrend.RISING && delta <= 0) {
                    trendExclusions.add(element.getStructureId());
                } else if (trend == TerrainTrend.FALLING && delta >= 0) {
                    trendExclusions.add(element.getStructureId());
                }
            }
        }
        boolean fallbackTriggered = false;
        while (candidates.size() < count && attempts++ < maxAttempts) {
            Set<String> currentExclusions = new HashSet<>();
            // Add Usage Constraints (Max Count)
            for (JigsawPool.WeightedElement element : pool.getElements()) {
                int currentCount = usageTracker.getCount(pool.getId(), element.getStructureId());
                int pendingCount = (int) candidates.stream().filter(c -> c.equals(element.getStructureId())).count();

                if (currentCount + pendingCount >= element.getMaxCount()) {
                    currentExclusions.add(element.getStructureId());
                }
            }
            // Add Trend Constraints (unless we already fell back)
            if (!fallbackTriggered) {
                currentExclusions.addAll(trendExclusions);
            }
            // Try selection
            String id = pool.selectRandomElementWithExclusions(random, currentExclusions);
            // If selection failed (e.g. all remaining weights are 0), check for fallback strategies
            if (id == null) {
                // Check if the failure was caused by Trend Exclusions blocking everything
                // (i.e., we have pieces remaining, but they were filtered out by the trend logic)
                if (!fallbackTriggered && !trendExclusions.isEmpty()) {
                    // Strategy: Terminator Priority
                    // Identify valid terminators (that are NOT excluded by usage limits)
                    Set<String> validTerminators = new HashSet<>();
                    for (JigsawPool.WeightedElement element : pool.getElements()) {
                        if (element.isTerminator()) {
                            // Re-check usage limits for this specific element
                            int currentCount = usageTracker.getCount(pool.getId(), element.getStructureId());
                            int pendingCount = (int) candidates.stream().filter(c -> c.equals(element.getStructureId())).count();
                            if (currentCount + pendingCount < element.getMaxCount()) {
                                validTerminators.add(element.getStructureId());
                            }
                        }
                    }
                    if (!validTerminators.isEmpty()) {
                        // Force selection from terminators only
                        // We construct an exclusion set that bans everything EXCEPT our valid terminators
                        Set<String> terminatorOnlyExclusions = new HashSet<>();
                        for (JigsawPool.WeightedElement element : pool.getElements()) {
                            if (!validTerminators.contains(element.getStructureId())) {
                                terminatorOnlyExclusions.add(element.getStructureId());
                            }
                        }
                        // Attempt selection again with the terminator-only set
                        id = pool.selectRandomElementWithExclusions(random, terminatorOnlyExclusions);
                        if (id != null) {
                            LOGGER.info("[Terrain] Selected Cap '" + id + "' due to " + trend + " terrain.");
                            // Proceed to add this ID to candidates
                        }
                    }
                    // If we still don't have an ID (no terminators found or selection failed), fall back to standard
                    // weights
                    if (id == null) {
                        LOGGER.info("[Terrain] Forced placement required (No caps found for " + trend
                                + " terrain). Falling back to standard weights.");
                        fallbackTriggered = true;
                        continue; // Retry loop immediately without trend exclusions
                    }
                } else {
                    // Truly exhausted (max counts reached for everything, or pool is empty)
                    break;
                }
            }
            // Add selected ID to candidates
            if (id != null && !selected.contains(id)) {
                candidates.add(id);
                selected.add(id);
            }
        }
        return candidates;
    }
}