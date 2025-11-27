package com.ionsignal.minecraft.ionnerrus.persona.components;

import org.bukkit.Location;
import org.bukkit.inventory.PlayerInventory;

import javax.annotation.Nullable;

/**
 * Read-only state queries (always synchronous).
 */
public interface StateCapability {
    /**
     * Gets the current location of the physical body.
     */
    Location getLocation();

    /**
     * Checks if the physical body is currently locked in an inventory interaction.
     */
    boolean isInventoryOpen();

    /**
     * Gets the Bukkit inventory view.
     */
    @Nullable
    PlayerInventory getInventory();

    /**
     * Checks if the entity is alive and valid.
     */
    boolean isAlive();
}