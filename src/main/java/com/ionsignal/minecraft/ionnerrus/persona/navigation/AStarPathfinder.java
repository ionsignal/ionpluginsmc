package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.AABB;

import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;

import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class AStarPathfinder {
    private static final int MAX_ITERATIONS = 16500;
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    public static CompletableFuture<Optional<Path>> findPath(Location start, Location end, NavigationParameters params) {
        BlockPos startPos = new BlockPos(start.getBlockX(), start.getBlockY(), start.getBlockZ());
        BlockPos endPos = new BlockPos(end.getBlockX(), end.getBlockY(), end.getBlockZ());
        int padding = 24; // Accounts for more complex pathing scenarios like falling around obstacles
        BlockPos min = new BlockPos(
                Math.min(startPos.getX(), endPos.getX()) - padding,
                Math.min(startPos.getY(), endPos.getY()) - padding,
                Math.min(startPos.getZ(), endPos.getZ()) - padding);
        BlockPos max = new BlockPos(
                Math.max(startPos.getX(), endPos.getX()) + padding,
                Math.max(startPos.getY(), endPos.getY()) + padding,
                Math.max(startPos.getZ(), endPos.getZ()) + padding);
        WorldSnapshot snapshot = new WorldSnapshot(start.getWorld(), min, max);
        return CompletableFuture.supplyAsync(
                () -> new AStarPathfinder(startPos, endPos, start.getWorld(), params, snapshot).calculatePath(),
                IonNerrus.getInstance().getOffloadThreadExecutor());
    }

    private final BlockPos startPos;
    private final BlockPos endPos;
    private final World world;
    private final NavigationParameters params;
    private final WorldSnapshot snapshot;

    private AStarPathfinder(BlockPos startPos, BlockPos endPos, World world, NavigationParameters params, WorldSnapshot snapshot) {
        this.startPos = startPos;
        this.endPos = endPos;
        this.world = world;
        this.params = params;
        this.snapshot = snapshot;
    }

    private Optional<Path> calculatePath() {
        LOGGER.info(String.format("Starting A* pathfinding from %s to %s", startPos.toString(), endPos.toString()));
        BlockPos actualStartPos = resolveStartPosition(startPos);
        BlockPos actualEndPos = endPos;
        if (actualStartPos == null || actualEndPos == null) {
            LOGGER.info("Could not find valid ground for start or end position, continuing...");
            return Optional.empty();
        }
        // Prepare for pathing
        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Node startNode = new Node(actualStartPos, null, 0, getHeuristic(actualStartPos, actualEndPos));
        openSet.add(startNode);
        allNodes.put(actualStartPos, startNode);
        // Begin iterations
        long startTime = System.nanoTime();
        int iterations = 0;
        while (!openSet.isEmpty() && iterations++ < MAX_ITERATIONS) {
            Node currentNode = openSet.poll();
            if (currentNode.pos.equals(actualEndPos)) {
                Path path = reconstructPath(currentNode);
                long endTime = System.nanoTime();
                long durationNanos = endTime - startTime;
                double durationMillis = durationNanos / 1_000_000.0;
                LOGGER.info(String.format("Path found after in %.3f ms %d iterations with %d points.", durationMillis, iterations,
                        path.size()));
                return Optional.of(path);
            }
            for (Node neighbor : getNeighbors(currentNode)) {
                if (allNodes.containsKey(neighbor.pos) && allNodes.get(neighbor.pos).gCost <= neighbor.gCost) {
                    continue;
                }
                allNodes.put(neighbor.pos, neighbor);
                openSet.add(neighbor);
            }
        }
        LOGGER.warning(String.format("Pathfinding failed after %d iterations. Open set size: %d", iterations, openSet.size()));
        return Optional.empty();
    }

    private BlockPos resolveStartPosition(BlockPos pos) {
        if (isWater(pos) && params.canSwim()) {
            return pos;
        }
        return findGround(pos);
    }

    private List<Node> getNeighbors(Node node) {
        List<Node> neighbors = new ArrayList<>();
        BlockPos currentPos = node.pos;
        // Add swimming neighbors
        if (params.canSwim() && isWater(currentPos)) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0)
                            continue;
                        // A valid swim destination is a water or air block with space for the head.
                        BlockPos swimPos = currentPos.offset(x, y, z);
                        if (isPassableForSwimming(swimPos) && isAirOrPassable(snapshot.getBlockState(swimPos.above()))) {
                            // If the swim destination is also a valid standing position (e.g. shallow water),
                            // let the more specific walking logic handle it to ensure correct costs.
                            if (isValidStandingPos(swimPos))
                                continue;
                            addNeighbor(neighbors, node, swimPos);
                        }
                    }
                }
            }
        }
        // Handle water-land transitions
        if (params.canSwim() && isWater(currentPos)) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0)
                        continue;
                    // Check for water exit points
                    BlockPos exitPos = currentPos.offset(x, 0, z);
                    if (!isWater(exitPos) && isValidStandingPos(exitPos)) {
                        // Can we exit water here?
                        if (canExitWaterTo(currentPos, exitPos)) {
                            addNeighbor(neighbors, node, exitPos);
                        }
                    }
                    // Check for jumping out of water onto higher ground
                    BlockPos jumpExitPos = currentPos.offset(x, 1, z);
                    if (!isWater(jumpExitPos) && isValidStandingPos(jumpExitPos)) {
                        if (canExitWaterTo(currentPos, jumpExitPos)) {
                            addNeighbor(neighbors, node, jumpExitPos);
                        }
                    }
                    // Check for lily pads and similar surfaces
                    if (isWaterWithSurface(exitPos)) {
                        addNeighbor(neighbors, node, exitPos.above());
                    }
                }
            }
        }
        // Add walking and climbing neighbors
        // Restructured neighbor logic to handle straight and diagonal moves separately.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0)
                    continue;
                // Walk
                boolean isDiagonal = x != 0 && z != 0;
                BlockPos walkPos = currentPos.offset(x, 0, z);
                if (isValidStandingPos(walkPos)) {
                    // For diagonal moves, perform an additional clearance check.
                    if (!isDiagonal || isDiagonalMoveClear(currentPos, walkPos)) {
                        addNeighbor(neighbors, node, walkPos);
                    }
                } else if (isWater(walkPos) && isAirOrPassable(snapshot.getBlockState(walkPos.above()))) {
                    // This handles walking from land directly into a water block.
                    // Also check clearance when moving diagonally into water.
                    if (!isDiagonal || isDiagonalMoveClear(currentPos, walkPos)) {
                        addNeighbor(neighbors, node, walkPos);
                    }
                }
                // Climb
                BlockPos climbPos = currentPos.offset(x, 1, z);
                if (params.climbHeight() >= 1 && isValidStandingPos(climbPos) && hasHeadroomForJump(currentPos)) {
                    // For diagonal climbs, perform an additional clearance check.
                    if (!isDiagonal || isDiagonalMoveClear(currentPos, climbPos)) {
                        addNeighbor(neighbors, node, climbPos);
                    }
                }
            }
        }
        // Add drop down neighbors
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0)
                    continue;
                // Check if we can fall from here
                // First, check if the space we'd step into before falling is clear.
                boolean isDiagonal = x != 0 && z != 0;
                BlockPos dropPos = currentPos.offset(x, 0, z);
                if (isAirOrPassable(snapshot.getBlockState(dropPos)) && isAirOrPassable(snapshot.getBlockState(dropPos.above()))) {
                    // Added check to prevent clipping corners when initiating a diagonal fall.
                    // This ensures the move from the current position to the edge we fall from is physically possible.
                    if (!isDiagonal || isDiagonalMoveClear(currentPos, dropPos)) {
                        BlockPos fallDestination = findLandingSpot(dropPos.below(), params.maxFallDistance());
                        if (fallDestination != null && !fallDestination.equals(currentPos)) {
                            if (isFallPathClear(dropPos, fallDestination)) {
                                addNeighbor(neighbors, node, fallDestination);
                            }
                        }
                    }
                }
            }
        }
        // Add drop straight down neighbors
        BlockState groundState = snapshot.getBlockState(currentPos.below());
        if (groundState != null && !groundState.isFaceSturdy(EmptyBlockGetter.INSTANCE, currentPos.below(), Direction.UP)) {
            BlockPos fallDestination = findLandingSpot(currentPos.below(), params.maxFallDistance());
            if (fallDestination != null && !fallDestination.equals(currentPos)) {
                if (isFallPathClear(currentPos, fallDestination)) {
                    addNeighbor(neighbors, node, fallDestination);
                }
            }
        }
        return neighbors;
    }

    private void addNeighbor(List<Node> neighbors, Node parent, BlockPos pos) {
        double gCost = parent.gCost + getMoveCost(parent.pos, pos);
        double hCost = getHeuristic(pos, endPos);
        neighbors.add(new Node(pos, parent, gCost, hCost));
    }

    private boolean isDiagonalMoveClear(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        // // EXPENSIVE LOGIC, IMPROVES PATHING (maybe enable indoors/caves)
        // // CHANGE: Check intermediate positions for entity collision box
        // // Entity occupies roughly 0.6x0.6 blocks, so check a slightly wider path
        // for (float t = 0.1f; t <= 0.9f; t += 0.2f) {
        // double checkX = from.getX() + dx * t;
        // double checkZ = from.getZ() + dz * t;
        // double checkY = from.getY() + (to.getY() - from.getY()) * t;
        // BlockPos checkPos = BlockPos.containing(checkX, checkY, checkZ);
        // if (!isPassableWithCollisionCheck(checkPos) ||
        // !isPassableWithCollisionCheck(checkPos.above())) {
        // return false;
        // }
        // }
        BlockPos corner1Base = from.offset(dx, 0, 0);
        BlockPos corner2Base = from.offset(0, 0, dz);
        // Check at destination Y-level
        BlockPos corner1AtDestY = corner1Base.atY(to.getY());
        BlockPos corner2AtDestY = corner2Base.atY(to.getY());
        // Enhanced check for partial blocks using collision shapes
        if (!isPassableWithCollisionCheck(corner1AtDestY) || !isPassableWithCollisionCheck(corner1AtDestY.above())) {
            return false;
        }
        if (!isPassableWithCollisionCheck(corner2AtDestY) || !isPassableWithCollisionCheck(corner2AtDestY.above())) {
            return false;
        }
        // Additional check for stairs and slabs at the corners
        BlockState corner1State = snapshot.getBlockState(corner1AtDestY);
        BlockState corner2State = snapshot.getBlockState(corner2AtDestY);
        if (isPartialBlock(corner1State) || isPartialBlock(corner2State)) {
            // For partial blocks, do a more thorough collision check
            return checkPartialBlockClearance(from, to, corner1AtDestY, corner2AtDestY);
        }
        return true;
    }

    private boolean isPassableWithCollisionCheck(BlockPos pos) {
        BlockState state = snapshot.getBlockState(pos);
        if (state == null)
            return false;
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos);
        if (shape.isEmpty()) {
            return true;
        }
        // Check if the collision shape allows passage
        AABB entityBox = new AABB(0.3, 0, 0.3, 0.7, 1.0, 0.7);
        return !shape.bounds().intersects(entityBox);
    }

    private boolean isPartialBlock(BlockState state) {
        if (state == null)
            return false;
        Material mat = state.getBukkitMaterial();
        return Tag.SLABS.isTagged(mat) ||
                Tag.STAIRS.isTagged(mat) ||
                Tag.FENCES.isTagged(mat) ||
                Tag.WALLS.isTagged(mat) ||
                Tag.FENCE_GATES.isTagged(mat);
    }

    private boolean checkPartialBlockClearance(BlockPos from, BlockPos to, BlockPos corner1, BlockPos corner2) {
        // Use collision shapes to determine if diagonal movement is possible
        BlockState state1 = snapshot.getBlockState(corner1);
        BlockState state2 = snapshot.getBlockState(corner2);
        if (state1 != null) {
            VoxelShape shape1 = state1.getCollisionShape(EmptyBlockGetter.INSTANCE, corner1);
            if (!shape1.isEmpty() && shape1.max(Direction.Axis.Y) > 0.5) {
                return false; // Too tall to step over diagonally
            }
        }
        if (state2 != null) {
            VoxelShape shape2 = state2.getCollisionShape(EmptyBlockGetter.INSTANCE, corner2);
            if (!shape2.isEmpty() && shape2.max(Direction.Axis.Y) > 0.5) {
                return false; // Too tall to step over diagonally
            }
        }
        return true;
    }

    private boolean canExitWaterTo(BlockPos waterPos, BlockPos landPos) {
        // Check if we can exit water to this position
        BlockPos aboveLand = landPos.above();
        if (!isAirOrPassable(snapshot.getBlockState(aboveLand))) {
            return false; // No headroom to exit
        }
        // Check if the exit requires jumping (higher than water level)
        if (landPos.getY() > waterPos.getY()) {
            // Need space above water to jump
            return isAirOrPassable(snapshot.getBlockState(waterPos.above()));
        }
        return true;
    }

    private boolean isWaterWithSurface(BlockPos pos) {
        if (!isWater(pos))
            return false;
        BlockState above = snapshot.getBlockState(pos.above());
        if (above == null)
            return false;
        Material mat = above.getBukkitMaterial();
        return mat == Material.LILY_PAD || Tag.WOOL_CARPETS.isTagged(mat) ||
                (mat.isSolid() && above.getCollisionShape(EmptyBlockGetter.INSTANCE, pos.above()).isEmpty());
    }

    private boolean isFallPathClear(BlockPos takeoffPos, BlockPos landingPos) {
        // The landing spot itself is already validated by `isValidStandingPos`.
        // We need to check the column of air the entity falls through.
        // The fall starts at the takeoff Y-level and at the landing X/Z coordinates.
        BlockPos.MutableBlockPos fallColumnPos = new BlockPos.MutableBlockPos(landingPos.getX(), 0, landingPos.getZ());
        // Iterate from the Y-level of the takeoff position down to the Y-level of the landing position.
        // We check the full 1x2 space at each Y-level of the fall.
        for (int y = takeoffPos.getY(); y >= landingPos.getY(); y--) {
            fallColumnPos.setY(y);
            // Check the space for the entity's feet.
            BlockState feetState = snapshot.getBlockState(fallColumnPos);
            if (!isAirOrPassable(feetState)) {
                return false;
            }
            // Check the space for the entity's head.
            BlockState headState = snapshot.getBlockState(fallColumnPos.above());
            if (!isAirOrPassable(headState)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidStandingPos(BlockPos pos) {
        BlockState ground = snapshot.getBlockState(pos.below());
        if (ground == null) {
            return false;
        }
        // CHANGE: Allow standing on leaves, which are not considered "sturdy" but are walkable.
        // PROBLEM: Causes issues because now we path incorrectly to get fallen blocks.
        boolean canStandOn = ground.isFaceSturdy(EmptyBlockGetter.INSTANCE, pos.below(), Direction.UP)
                || Tag.LEAVES.isTagged(ground.getBukkitMaterial());
        if (!canStandOn) {
            return false;
        }
        // END CHANGE
        // boolean canStandOn = ground.isFaceSturdy(EmptyBlockGetter.INSTANCE, pos.below(), Direction.UP);
        // if (!canStandOn) {
        // return false;
        // }
        BlockState feet = snapshot.getBlockState(pos);
        if (feet == null || !isAirOrPassable(feet)) {
            return false;
        }
        BlockState head = snapshot.getBlockState(pos.above());
        return head != null && isAirOrPassable(head);
    }

    private boolean hasHeadroomForJump(BlockPos pos) {
        // A persona at `pos` occupies `pos` and `pos.above(1)`. To jump, the block at `pos.above(2)`
        // must be clear to avoid hitting their head. The original check was redundant.
        BlockState twoAbove = snapshot.getBlockState(pos.above(2));
        return twoAbove != null && isAirOrPassable(twoAbove);
    }

    private boolean isWater(BlockPos pos) {
        if (!params.canSwim())
            return false;
        BlockState state = snapshot.getBlockState(pos);
        return state != null && state.getBukkitMaterial() == Material.WATER;
    }

    private boolean isPassableForSwimming(BlockPos pos) {
        BlockState state = snapshot.getBlockState(pos);
        if (state == null)
            return false;
        Material mat = state.getBukkitMaterial();
        // Can swim into water or air (for surfacing).
        return mat == Material.WATER || mat.isAir();
    }

    private boolean isAirOrPassable(BlockState state) {
        if (state == null)
            return false;
        Material mat = state.getBukkitMaterial();
        if (Tag.LEAVES.isTagged(mat)) {
            return false;
        }
        if (mat.isAir())
            return true;
        if (mat == Material.WATER && params.canSwim())
            return true;
        // Use collision shape as the primary check. This correctly handles fences, walls, leaves, etc.
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    private BlockPos findGround(BlockPos start) {
        // Determine search range based on terrain/biome
        int searchRange = getSearchRangeForLocation(start);
        return findGround(start, searchRange);
    }

    private int getSearchRangeForLocation(BlockPos pos) {
        // Get biome at position to determine appropriate search range
        Block block = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
        Biome biome = block.getBiome();
        String biomeName = biome.getKey().getKey();
        if (biomeName.contains("mountain") || biomeName.contains("peak") ||
                biomeName.contains("slope") || biomeName.contains("cliff")) {
            return 30; // Mountains need more vertical search
        }
        if (biomeName.contains("cave") || biomeName.contains("deep")) {
            return 25; // Caves and deep dark need extensive search
        }
        if (biomeName.contains("ocean") || biomeName.contains("river")) {
            return 20; // Water biomes might need to search deeper
        }
        if (biomeName.contains("jungle") || biomeName.contains("forest")) {
            return 15; // Trees might require some vertical search
        }
        return 10; // Default for relatively flat biomes
    }

    private BlockPos findGround(BlockPos start, int searchRange) {
        // Search down first
        BlockPos current = start;
        for (int i = 0; i < searchRange; i++) {
            if (isValidStandingPos(current))
                return current;
            current = current.below();
        }
        // If not found, search up from original start
        current = start.above();
        for (int i = 0; i < searchRange; i++) {
            if (isValidStandingPos(current))
                return current;
            current = current.above();
        }
        return null; // No valid ground found in range
    }

    private BlockPos findLandingSpot(BlockPos start, int searchRange) {
        BlockPos current = start;
        for (int i = 0; i < searchRange; i++) {
            // A valid landing spot is either a valid standing position OR a water block.
            if (isValidStandingPos(current) || isWater(current)) {
                return current;
            }
            current = current.below();
        }
        return null; // No valid landing spot found in range.
    }

    private double getMoveCost(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dy = Math.abs(from.getY() - to.getY());
        int dz = Math.abs(from.getZ() - to.getZ());
        // Penalize vertical movement to prefer flatter paths
        double verticalCost = dy * 1.5;
        // Use standard diagonal distance for horizontal movement
        double horizontalCost;
        if (dx >= 1 && dz >= 1) { // Corrected diagonal check
            horizontalCost = 1.414;
        } else {
            horizontalCost = dx + dz;
        }
        double totalCost = horizontalCost + verticalCost;
        // Additional cost modifiers
        if (isWater(to)) {
            totalCost *= 4.0; // Water is expensive
        }
        // Increase cost for water-land transitions
        if (isWater(from) && !isWater(to)) {
            totalCost *= 1.5; // Exiting water has extra cost
        }
        // Check for difficult terrain
        BlockState toState = snapshot.getBlockState(to.below());
        if (toState != null) {
            Material mat = toState.getBukkitMaterial();
            if (mat == Material.SOUL_SAND || mat == Material.HONEY_BLOCK) {
                totalCost *= 2.0; // Slow blocks are more expensive
            }
        }
        return totalCost;
    }

    private double getHeuristic(BlockPos a, BlockPos b) {
        // Manhattan distance is a good, fast heuristic for grid-based worlds
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private Path reconstructPath(Node endNode) {
        LinkedList<BlockPos> path = new LinkedList<>();
        Node currentNode = endNode;
        while (currentNode != null) {
            path.addFirst(currentNode.pos);
            currentNode = currentNode.parent;
        }
        return new Path(path, this.world);
    }

    private static class Node implements Comparable<Node> {
        final BlockPos pos;
        final Node parent;
        final double gCost;
        final double fCost;

        Node(BlockPos pos, Node parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.fCost = gCost + hCost;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.fCost, other.fCost);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            return pos.equals(((Node) o).pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }
}