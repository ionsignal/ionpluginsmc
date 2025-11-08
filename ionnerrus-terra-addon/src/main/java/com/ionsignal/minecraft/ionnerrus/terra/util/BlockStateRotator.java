package com.ionsignal.minecraft.ionnerrus.terra.util;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.util.Rotation;

import org.bukkit.craftbukkit.block.data.CraftBlockData;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.StateHolder;

import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Utility to rotate BlockState properties using the underlying NMS implementation.
 * This ensures accurate rotation for all block types, including complex ones like stairs and doors.
 */
public final class BlockStateRotator {
    private static final Logger LOGGER = Logger.getLogger(BlockStateRotator.class.getName());

    private BlockStateRotator() {
        // Private constructor to prevent instantiation
        LOGGER.log(Level.INFO, "BlockStateRotator Object Created...");
    }

    /**
     * Rotates a Terra BlockState using the native Minecraft server's rotation logic.
     *
     * @param state
     *            The original BlockState from the Terra API.
     * @param rotation
     *            The rotation to apply, from Terra's Rotation enum.
     * @param platform
     *            The Terra Platform instance, used to wrap the result.
     * @return The rotated BlockState, or the original state if rotation is NONE or handle is invalid.
     */
    public static BlockState rotate(BlockState state, Rotation rotation, Platform platform) {
        if (state == null || rotation == Rotation.NONE) {
            return state;
        }
        LOGGER.log(Level.FINE, "Attempting to rotate state: {0} by {1}", new Object[] { state.getAsString(), rotation });
        Object handle = state.getHandle();
        // Add defensive type checking and proper unwrapping
        // The handle from Terra's Paper implementation is always CraftBlockData
        if (!(handle instanceof CraftBlockData craftBlockData)) {
            LOGGER.log(Level.WARNING, "Cannot rotate BlockState: Handle is not CraftBlockData. Type is {0}", handle.getClass().getName());
            return state; // Fallback to original state
        }
        // Extract the NMS BlockState from CraftBlockData
        net.minecraft.world.level.block.state.BlockState nmsState = craftBlockData.getState();
        if (nmsState == null) {
            LOGGER.log(Level.WARNING, "Cannot rotate: CraftBlockData.getState() returned null");
            return state; // Fallback to original state
        }
        // Convert Terra rotation to NMS rotation
        net.minecraft.world.level.block.Rotation nmsRotation = toNMSRotation(rotation);
        // Perform the rotation using the NMS method
        net.minecraft.world.level.block.state.BlockState rotatedNmsState = nmsState.rotate(nmsRotation);
        // Use Minecraft's built-in serialization instead of custom string builder
        // This ensures all properties (including complex ones like stair 'shape') are handled correctly
        String serialized = serializeNMSState(rotatedNmsState);
        LOGGER.log(Level.FINE, "Serialized rotated state as: {0}", serialized);
        // Pass the string to Terra's createBlockState method
        // This lets Terra handle the proper wrapping back to its API
        return platform.getWorldHandle().createBlockState(serialized);
    }

    /**
     * Serializes an NMS BlockState to Minecraft's standard format.
     * Uses Minecraft's built-in property serialization to ensure correctness.
     * 
     * Format: "namespace:block_id[prop1=val1,prop2=val2]"
     * 
     * @param nmsState
     *            The NMS BlockState to serialize
     * @return A string representation compatible with Minecraft/Bukkit parsers
     */
    private static String serializeNMSState(net.minecraft.world.level.block.state.BlockState nmsState) {
        // Get the block's namespaced ID (e.g., "minecraft:oak_stairs")
        StringBuilder sb = new StringBuilder(
                BuiltInRegistries.BLOCK.getKey(nmsState.getBlock()).toString());
        // Get the map of properties from the state
        var properties = nmsState.getValues();
        if (!properties.isEmpty()) {
            sb.append('[');
            // CRITICAL: Use Minecraft's built-in PROPERTY_ENTRY_TO_STRING_FUNCTION
            // This handles all property types correctly (enums, booleans, integers, etc.)
            String propsString = properties.entrySet().stream()
                    .map(StateHolder.PROPERTY_ENTRY_TO_STRING_FUNCTION)
                    .collect(Collectors.joining(","));

            sb.append(propsString);
            sb.append(']');
        }
        return sb.toString();
    }

    /**
     * Converts a Terra Rotation enum to its NMS equivalent.
     *
     * @param rotation
     *            The Terra Rotation.
     * @return The corresponding NMS Rotation.
     */
    private static net.minecraft.world.level.block.Rotation toNMSRotation(Rotation rotation) {
        return switch (rotation) {
            case NONE -> net.minecraft.world.level.block.Rotation.NONE;
            case CW_90 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case CW_180 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case CCW_90 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
        };
    }
}