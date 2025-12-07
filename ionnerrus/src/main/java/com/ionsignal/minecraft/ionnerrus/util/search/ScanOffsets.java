package com.ionsignal.minecraft.ionnerrus.util.search;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * A pure, thread-safe utility class to hold pre-calculated coordinate offsets for geometric shapes.
 * This avoids redundant and expensive calculations within hot loops.
 */
public final class ScanOffsets {
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();
    private static final int MAX_RADIUS_CAP = 10;
    private static final Map<Integer, List<BlockPos>> CACHE = new ConcurrentHashMap<>();

    private ScanOffsets() {
        // Prevent instantiation
    }

    /**
     * Retrieves (or computes) a list of offsets representing a half-sphere of the given radius.
     * 
     * @param radius
     *            The radius of the sphere. Clamped between 1 and MAX_RADIUS_CAP (10).
     * @return An unmodifiable list of BlockPos offsets.
     */
    public static List<BlockPos> getHalfSphere(int radius) {
        // Clamp radius to prevent cubic scaling explosions or invalid inputs
        int r = Math.min(Math.max(1, radius), MAX_RADIUS_CAP);
        return CACHE.computeIfAbsent(r, key -> {
            LOGGER.info("Computing spherical scan offsets for radius " + key + "...");
            List<BlockPos> offsets = new ArrayList<>();
            int rSquared = key * key;
            for (int x = -key; x <= key; x++) {
                for (int z = -key; z <= key; z++) {
                    // y starts at 0 for a half-sphere (feet upwards)
                    for (int y = 0; y <= key; y++) {
                        if (x * x + y * y + z * z <= rSquared) {
                            offsets.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            return Collections.unmodifiableList(offsets);
        });
    }
}