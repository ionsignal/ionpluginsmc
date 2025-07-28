package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.*;
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
        BlockPos actualStartPos = findGround(startPos);
        BlockPos actualEndPos = findGround(endPos);

        if (actualStartPos == null || actualEndPos == null) {
            LOGGER.info("Could not find valid ground for start or end position, continuing...");
            return Optional.empty();
        }

        Node startNode = new Node(actualStartPos, null, 0, getHeuristic(actualStartPos, actualEndPos));
        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Node> allNodes = new HashMap<>();

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
        // Add walking and climbing neighbors
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0)
                    continue;
                // Walk
                BlockPos walkPos = currentPos.offset(x, 0, z);
                if (isValidStandingPos(walkPos)) {
                    addNeighbor(neighbors, node, walkPos);
                }
                // Climb
                BlockPos climbPos = currentPos.offset(x, 1, z);
                if (params.climbHeight() >= 1 && isValidStandingPos(climbPos) && hasHeadroomForJump(currentPos)) {
                    addNeighbor(neighbors, node, climbPos);
                }
            }
        }
        // Add falling neighbors
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0)
                    continue;
                // Check if we can fall from here
                BlockPos adjacentPos = currentPos.offset(x, 0, z);
                if (isAirOrPassable(snapshot.getBlockState(adjacentPos)) && isAirOrPassable(snapshot.getBlockState(adjacentPos.above()))) {
                    BlockPos fallDestination = findGround(adjacentPos.below(), params.maxFallDistance());
                    if (fallDestination != null && !fallDestination.equals(currentPos)) {
                        addNeighbor(neighbors, node, fallDestination);
                    }
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
        BlockState above = snapshot.getBlockState(pos.above(1));
        BlockState twoAbove = snapshot.getBlockState(pos.above(2));
        return above != null && isAirOrPassable(above) && twoAbove != null && isAirOrPassable(twoAbove);
    }

    private boolean isAirOrPassable(BlockState state) {
        if (state == null)
            return false;
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

    private double getMoveCost(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dy = Math.abs(from.getY() - to.getY());
        int dz = Math.abs(from.getZ() - to.getZ());
        // Penalize vertical movement to prefer flatter paths
        double verticalCost = dy * 1.5;
        // Use standard diagonal distance for horizontal movement
        if (dx == 1 && dz == 1) {
            return 1.414 + verticalCost;
        }
        return dx + dz + verticalCost;
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