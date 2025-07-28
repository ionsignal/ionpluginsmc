package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.HashMap;
import java.util.Map;

/**
 * A thread-safe, in-memory snapshot of a region of the world for fast, asynchronous access.
 */
public class WorldSnapshot {
    private final Map<BlockPos, BlockState> blocks;

    public WorldSnapshot(World world, BlockPos corner1, BlockPos corner2) {
        int minX = Math.min(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());
        this.blocks = new HashMap<>();

        // This is a synchronous, main-thread operation to gather all data upfront.
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    blocks.put(pos, ((CraftWorld) world).getHandle().getBlockState(pos));
                }
            }
        }
    }

    public BlockState getBlockState(BlockPos pos) {
        return blocks.get(pos);
    }
}