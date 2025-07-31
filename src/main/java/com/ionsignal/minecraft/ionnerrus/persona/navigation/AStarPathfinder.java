package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.bukkit.Location;
import org.bukkit.Material;
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

        BlockPos actualStartPos = isWater(startPos) && params.canSwim() ? startPos : findGround(startPos);
        BlockPos actualEndPos = isWater(endPos) && params.canSwim() ? endPos : findGround(endPos);

        if (actualStartPos == null || actualEndPos == null) {
            LOGGER.info("Could not find valid ground for start or end position, continuing...");
            return Optional.empty();
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Node startNode = new Node(actualStartPos, null, 0, getHeuristic(actualStartPos, actualEndPos));

        openSet.add(startNode);
        allNodes.put(actualStartPos, startNode);

        int iterations = 0;
        while (!openSet.isEmpty() && iterations++ < MAX_ITERATIONS) {
            Node currentNode = openSet.poll();
            if (currentNode.pos.equals(actualEndPos)) {
                Path path = reconstructPath(currentNode);
                LOGGER.info(String.format("Path found after %d iterations with %d points.", iterations, path.size()));
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
        // Identify the two intermediate corner blocks relative to the start position.
        // We check these corners at the destination Y-level.
        BlockPos corner1Base = from.offset(dx, 0, 0); // e.g., (x+1, y_from, z_from)
        BlockPos corner2Base = from.offset(0, 0, dz); // e.g., (x_from, y_from, z_from+1)
        // Check the 1x2 column at corner 1, but at the destination's height.
        BlockPos corner1AtDestY = corner1Base.atY(to.getY());
        if (!isAirOrPassable(snapshot.getBlockState(corner1AtDestY)) || !isAirOrPassable(snapshot.getBlockState(corner1AtDestY.above()))) {
            return false;
        }
        // Check the 1x2 column at corner 2, but at the destination's height.
        BlockPos corner2AtDestY = corner2Base.atY(to.getY());
        if (!isAirOrPassable(snapshot.getBlockState(corner2AtDestY)) || !isAirOrPassable(snapshot.getBlockState(corner2AtDestY.above()))) {
            return false;
        }

        return true;
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
        if (ground == null || !ground.isFaceSturdy(EmptyBlockGetter.INSTANCE, pos.below(), Direction.UP)) {
            return false;
        }
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
        if (state.is(BlockTags.LEAVES)) {
            return false;
        }
        Material mat = state.getBukkitMaterial();
        if (mat.isAir())
            return true;
        if (mat == Material.WATER && params.canSwim())
            return true;
        // Use collision shape as the primary check. This correctly handles fences, walls, leaves, etc.
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    private BlockPos findGround(BlockPos start) {
        return findGround(start, 10); // Search up/down 10 blocks by default
    }

    private BlockPos findGround(BlockPos start, int searchRange) {
        BlockPos current = start;
        // Search down first
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
        if (isWater(to)) {
            totalCost *= 4.0; // Make water significantly more "expensive" to path through
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