package com.ionsignal.minecraft.ionnerrus.agent.skills;

import org.bukkit.Location;

/**
 * A container for the result of finding an accessible block.
 * It bundles the target block with the optimal location from which to break it.
 *
 * @param blockLocation    The location of the block to be actioned upon.
 * @param standingLocation The location the agent should stand at to action the block.
 */
public record AccessibleBlockResult(Location blockLocation, Location standingLocation) {
}