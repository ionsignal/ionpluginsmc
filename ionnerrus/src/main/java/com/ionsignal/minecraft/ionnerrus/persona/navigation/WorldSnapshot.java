package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

/**
 * A thread-safe, in-memory snapshot of a region of the world for fast, asynchronous access.
 * This class is now created via an asynchronous factory method, `create`.
 * 
 * Implements BlockGetter to support NMS ray-tracing operations.
 */
public class WorldSnapshot implements BlockGetter {
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();
    private static final double WARN_THRESHOLD_MS = 100.0;
    private final Map<Long, ChunkSnapshot> chunkSnapshots;
    private final int worldMinHeight;
    private final int worldMaxHeight;

    /**
     * The constructor is now private and accepts the world's height limits.
     * 
     * @param chunkSnapshots
     *            A map of chunk snapshots keyed by their packed coordinates.
     * @param worldMinHeight
     *            The minimum Y level of the world.
     * @param worldMaxHeight
     *            The maximum Y level of the world.
     */
    private WorldSnapshot(Map<Long, ChunkSnapshot> chunkSnapshots, int worldMinHeight, int worldMaxHeight) {
        this.chunkSnapshots = chunkSnapshots;
        this.worldMinHeight = worldMinHeight;
        this.worldMaxHeight = worldMaxHeight;
    }

    /**
     * This is the new public, asynchronous factory method for creating a WorldSnapshot. It loads all
     * required chunks asynchronously, then captures their state on the main thread without blocking the
     * server.
     *
     * @param world
     *            The world to snapshot.
     * @param corner1
     *            The first corner of the bounding box.
     * @param corner2
     *            The second corner of the bounding box.
     * 
     * @return A CompletableFuture that will complete with the new WorldSnapshot instance.
     */
    public static CompletableFuture<WorldSnapshot> create(World world, BlockPos corner1, BlockPos corner2) {
        long startTime = System.nanoTime();
        // Calculate the range of chunks needed.
        int minChunkX = corner1.getX() >> 4;
        int maxChunkX = corner2.getX() >> 4;
        int minChunkZ = corner1.getZ() >> 4;
        int maxChunkZ = corner2.getZ() >> 4;
        // Asynchronously request all required chunks from Paper's API.
        List<CompletableFuture<Chunk>> chunkFutures = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunkFutures.add(world.getChunkAtAsync(cx, cz));
            }
        }
        // Wait for all chunk loading operations to complete.
        CompletableFuture<Void> allChunksLoaded = CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]));
        // Once all chunks are loaded, create the snapshots on the main thread.
        // This step MUST be on the main thread as `getChunkSnapshot` is not thread-safe.
        return allChunksLoaded.thenApplyAsync(v -> {
            Map<Long, ChunkSnapshot> snapshots = chunkFutures.stream()
                    .map(CompletableFuture::join) // .join() is now safe and non-blocking
                    .collect(Collectors.toMap(
                            chunk -> Chunk.getChunkKey(chunk.getX(), chunk.getZ()),
                            Chunk::getChunkSnapshot));
            // Log performance and create the final WorldSnapshot object.
            long endTime = System.nanoTime();
            double durationMillis = (endTime - startTime) / 1_000_000.0;
            String logMessage = String.format(
                    "WorldSnapshot created asynchronously in %.3f ms (%d chunks)",
                    durationMillis,
                    snapshots.size());
            if (durationMillis >= WARN_THRESHOLD_MS) {
                LOGGER.warning(logMessage);
            }
            // Pass the world's height limits into the constructor.
            return new WorldSnapshot(snapshots, world.getMinHeight(), world.getMaxHeight());
        }, IonNerrus.getInstance().getMainThreadExecutor());
    }

    /**
     * Performs a ray-trace (clip) through the snapshot data and acts as a helper method to avoid NMS
     * boilerplate in Skills. Defaults to checking for solid blocks and ignoring fluids.
     *
     * @param start
     *            The starting vector (e.g., Eye Location).
     * @param end
     *            The ending vector (e.g., Target Block Center).
     * 
     * @return The NMS BlockHitResult containing the position and face of block hit, or a Miss result.
     */
    @SuppressWarnings("null")
    public BlockHitResult rayTrace(Vector start, Vector end) {
        Vec3 startVec = new Vec3(start.getX(), start.getY(), start.getZ());
        Vec3 endVec = new Vec3(end.getX(), end.getY(), end.getZ());
        return this.clip(new ClipContext(
                startVec,
                endVec,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                CollisionContext.empty()));
    }

    @Override
    @SuppressWarnings("null")
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        // Snapshots do not capture tile entities.
        return null;
    }

    @Override
    @SuppressWarnings("null")
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return this.worldMaxHeight - this.worldMinHeight;
    }

    public int getMinBuildHeight() {
        return this.worldMinHeight;
    }

    public int getMinY() {
        return this.worldMinHeight;
    }

    /**
     * The logic for retrieving a block's state is completely rewritten to use the new chunk-based
     * storage system.
     * 
     * @param pos
     *            The BlockPos of the desired block.
     * 
     * @return The NMS BlockState. Returns AIR if outside snapshot bounds.
     */
    @Override
    @SuppressWarnings("null")
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = getBlockStateIfLoaded(pos);
        return state != null ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    @SuppressWarnings("null")
    public @Nullable BlockState getBlockStateIfLoaded(BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long chunkKey = Chunk.getChunkKey(chunkX, chunkZ);
        ChunkSnapshot snapshot = chunkSnapshots.get(chunkKey);
        if (snapshot == null) {
            return null; // Chunk not captured in snapshot
        }
        // Check against the stored world height limits.
        int y = pos.getY();
        if (y < this.worldMinHeight || y >= this.worldMaxHeight) {
            return null;
        }
        int intraChunkX = pos.getX() & 15;
        int intraChunkZ = pos.getZ() & 15;
        BlockData bukkitBlockData = snapshot.getBlockData(intraChunkX, y, intraChunkZ);
        // Convert Bukkit's BlockData to NMS BlockState for compatibility with pathfinder.
        return ((CraftBlockData) bukkitBlockData).getState();
    }

    @Override
    @SuppressWarnings("null")
    public @Nullable FluidState getFluidIfLoaded(BlockPos pos) {
        BlockState state = getBlockStateIfLoaded(pos);
        return state == null ? null : state.getFluidState();
    }

    /**
     * Raycasts downwards through the snapshot to find a solid landing surface.
     * This method is thread-safe and operates on the frozen snapshot data.
     *
     * @param startLocation
     *            The starting location (e.g., item entity position).
     * @param maxDepth
     *            The maximum number of blocks to check downwards.
     * 
     * @return The Location directly ABOVE the solid block found, or empty if air/fluid/void.
     */
    public Optional<Location> findGroundBelow(Location startLocation, int maxDepth) {
        MutableBlockPos pos = new MutableBlockPos(
                startLocation.getBlockX(),
                startLocation.getBlockY(),
                startLocation.getBlockZ());
        for (int i = 0; i < maxDepth; i++) {
            BlockState state = this.getBlockState(pos);
            if (!state.isAir()) {
                if (!state.getFluidState().isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new Location(
                        startLocation.getWorld(),
                        pos.getX() + 0.5,
                        pos.getY() + 1.0,
                        pos.getZ() + 0.5));
            }
            pos.move(Direction.DOWN);
        }
        return Optional.empty();
    }
}