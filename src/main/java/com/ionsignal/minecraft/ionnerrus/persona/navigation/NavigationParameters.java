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
public record NavigationParameters(int maxFallDistance, int climbHeight, boolean canSwim) {
    public static final NavigationParameters DEFAULT = new NavigationParameters(6, 1, false);
}