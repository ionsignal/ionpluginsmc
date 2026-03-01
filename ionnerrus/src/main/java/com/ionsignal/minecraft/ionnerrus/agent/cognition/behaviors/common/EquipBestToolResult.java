package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common;

import org.bukkit.Material;

import java.util.Optional;

/**
 * Describes the outcome of an attempt to equip the best tool for a block.
 */
public record EquipBestToolResult(Status status, Optional<Material> equippedMaterial) {

    public enum Status {
        /**
         * The best available tool was equipped, or the agent was already holding it.
         */
        SUCCESS,
        /**
         * No suitable tool was found in the inventory, and a default tool could not be provided.
         */
        NO_TOOL_AVAILABLE,
        /**
         * The physical swap action failed or the inventory was inaccessible.
         */
        FAILURE
    }

    public static EquipBestToolResult success(Material material) {
        return new EquipBestToolResult(Status.SUCCESS, Optional.ofNullable(material));
    }

    public static EquipBestToolResult noTool() {
        return new EquipBestToolResult(Status.NO_TOOL_AVAILABLE, Optional.empty());
    }

    public static EquipBestToolResult failure() {
        return new EquipBestToolResult(Status.FAILURE, Optional.empty());
    }
}