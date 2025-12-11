package com.ionsignal.minecraft.ionnerrus.persona.navigation;

/**
 * Holds parameters for a pathfinding operation.
 *
 * @param maxFallDistance
 *            The maximum distance a Persona can fall to reach a new block.
 * @param climbHeight
 *            The maximum height a Persona can climb in a single step.
 * @param canSwim
 *            Whether the Persona can traverse through water.
 */
public record NavigationParameters(
        int maxFallDistance,
        int climbHeight,
        boolean canSwim,
        int maxIterations) {
    // Cap at 2500 (Good for ~100 block complex paths)
    public static final NavigationParameters DEFAULT = new NavigationParameters(4, 1, true, 2500);
    // Cap at 8000 (For explicit long-distance travel)
    public static final NavigationParameters LONG_RANGE = new NavigationParameters(4, 1, true, 8000);
    // Cap at 500 (Good for local "can I stand there?" checks)
    public static final NavigationParameters SHORT_RANGE = new NavigationParameters(4, 1, true, 500);
}