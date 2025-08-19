package com.ionsignal.minecraft.ionnerrus.agent.skills;

import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import org.bukkit.Location;

/**
 * An immutable container for the result of finding the optimal block to collect.
 * It bundles the target block, the optimal standing location from which to break it,
 * and the pre-calculated path to that location.
 *
 * @param blockLocation
 *            The location of the block to be collected.
 * @param standingLocation
 *            The location the agent should stand at to collect the block.
 * @param pathToStand
 *            The pre-calculated path from the agent's current location to the standingLocation.
 */
public record CollectableTarget(Location blockLocation, Location standingLocation, Path pathToStand) {
}