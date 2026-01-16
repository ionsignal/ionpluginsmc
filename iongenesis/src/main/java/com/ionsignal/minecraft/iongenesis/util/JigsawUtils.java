package com.ionsignal.minecraft.iongenesis.util;

import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Utilities for Jigsaw generation compatible with Terra 7.0.0 APIs.
 */
public final class JigsawUtils {

    private JigsawUtils() {
    }

    /**
     * Shuffles a list using a RandomGenerator (Fisher-Yates shuffle).
     * Replaces Collections.shuffle(List, Random) which is incompatible with RandomGenerator.
     *
     * @param list
     *            The list to shuffle.
     * @param random
     *            The RandomGenerator to use.
     */
    public static void shuffle(List<?> list, RandomGenerator random) {
        int size = list.size();
        for (int i = size; i > 1; i--) {
            Collections.swap(list, i - 1, random.nextInt(i));
        }
    }
}