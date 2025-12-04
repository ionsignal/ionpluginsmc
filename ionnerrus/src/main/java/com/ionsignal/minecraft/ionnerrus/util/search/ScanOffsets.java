package com.ionsignal.minecraft.ionnerrus.util.search;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * A pure, thread-safe utility class to hold pre-calculated coordinate offsets for geometric shapes.
 * This avoids redundant and expensive calculations within hot loops.
 */
public final class ScanOffsets {
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    private static final int REACH_RADIUS = 6;
    public static final int REACH_RADIUS_SQUARED = REACH_RADIUS * REACH_RADIUS;
    public static final List<BlockPos> HALF_SPHERE_REACH_OFFSETS;

    private ScanOffsets() {
        // Prevent instantiation
    }

    /**
     * This static initializer runs exactly once when the class is loaded.
     * It performs the expensive calculation of all offsets within a half-sphere and caches the result
     * in an immutable list for fast, repeated access.
     */
    static {
        LOGGER.info("Pre-calculating spherical scan offsets for agent reach...");
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -REACH_RADIUS; x <= REACH_RADIUS; x++) {
            for (int z = -REACH_RADIUS; z <= REACH_RADIUS; z++) {
                // Note: y starts at 0 for a half-sphere, representing what an agent can see/reach from their feet
                // level upwards.
                for (int y = 0; y <= REACH_RADIUS; y++) {
                    if (x * x + y * y + z * z <= REACH_RADIUS_SQUARED) {
                        offsets.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        HALF_SPHERE_REACH_OFFSETS = Collections.unmodifiableList(offsets);
        LOGGER.info("Successfully pre-calculated " + HALF_SPHERE_REACH_OFFSETS.size() + " scan offsets.");
    }
}