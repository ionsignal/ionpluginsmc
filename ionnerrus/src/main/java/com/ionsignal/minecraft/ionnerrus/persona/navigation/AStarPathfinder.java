package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult.MovementType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class AStarPathfinder {
    private static final double WARN_THRESHOLD_MS = 250.0;
    private static final int MAX_ITERATIONS = 8000;
    private static final int MAX_BREATH_TICKS = 200;
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    private final PathfindingRequest request;
    private final WorldSnapshot snapshot;
    private final ExecutionToken token;

    private AStarPathfinder(PathfindingRequest request, WorldSnapshot snapshot, ExecutionToken token) {
        this.request = request;
        this.snapshot = snapshot;
        this.token = token;
    }

    /**
     * Public Entry Point.
     * Calculates bounds, creates the snapshot asynchronously, then executes the pathfinder.
     */
    public static CompletableFuture<Optional<Path>> findPath(PathfindingRequest request, ExecutionToken token) {
        BlockPos startPos = request.startBlock();
        BlockPos endPos = request.endBlock();
        int padding = 16;
        BlockPos min = new BlockPos(
                Math.min(startPos.getX(), endPos.getX()) - padding,
                Math.min(startPos.getY(), endPos.getY()) - padding,
                Math.min(startPos.getZ(), endPos.getZ()) - padding);
        BlockPos max = new BlockPos(
                Math.max(startPos.getX(), endPos.getX()) + padding,
                Math.max(startPos.getY(), endPos.getY()) + padding,
                Math.max(startPos.getZ(), endPos.getZ()) + padding);
        return WorldSnapshot.create(request.start().getWorld(), min, max)
                .thenCompose(snapshot -> CompletableFuture.supplyAsync(
                        () -> new AStarPathfinder(request, snapshot, token).calculatePath(),
                        IonNerrus.getInstance().getOffloadThreadExecutor()));
    }

    /**
     * Public Entry Point (Immediate).
     * Executes pathfinding synchronously on the calling thread using an EXISTING snapshot.
     * Use this when running inside a Skill that has already captured world state.
     */
    public static Optional<Path> computeImmediate(PathfindingRequest request, WorldSnapshot snapshot, ExecutionToken token) {
        return new AStarPathfinder(request, snapshot, token).calculatePath();
    }

    private Optional<Path> calculatePath() {
        // 1. Resolve the exact starting node using physics-aware logic
        BlockPos actualStartPos = resolveStartNode();
        BlockPos actualEndPos = request.endBlock();
        if (actualStartPos == null) {
            LOGGER.info("Could not find valid ground for start position.");
            return Optional.empty();
        }
        // 2. Setup A*
        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Node startNode = new Node(actualStartPos, null, 0, getHeuristic(actualStartPos, actualEndPos), 0);
        openSet.add(startNode);
        allNodes.put(actualStartPos, startNode);
        long startTime = System.nanoTime();
        int iterations = 0;
        // 3. Run A*
        while (!openSet.isEmpty() && iterations++ < MAX_ITERATIONS) {
            if (token != null && !token.isActive()) {
                throw new CancellationException("Pathfinding cancelled via execution token");
            }
            Node currentNode = openSet.poll();
            if (currentNode.pos.equals(actualEndPos)) {
                Path path = reconstructAndEnrichPath(currentNode);
                // Performance Logging
                long endTime = System.nanoTime();
                double durationMillis = (endTime - startTime) / 1_000_000.0;
                if (durationMillis > WARN_THRESHOLD_MS) {
                    LOGGER.warning(String.format("Path found in %.3f ms %d iterations.", durationMillis, iterations));
                }
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
        LOGGER.warning(String.format("Pathfinding failed after %d iterations.", iterations));
        return Optional.empty();
    }

    /**
     * Calculates metadata clearance, movement type, apex radius for each node.
     */
    private Path reconstructAndEnrichPath(Node endNode) {
        LinkedList<BlockPos> rawPositions = new LinkedList<>();
        Node currentNode = endNode;
        while (currentNode != null) {
            rawPositions.addFirst(currentNode.pos);
            currentNode = currentNode.parent;
        }
        List<PathNode> enrichedNodes = new ArrayList<>(rawPositions.size());
        for (int i = 0; i < rawPositions.size(); i++) {
            BlockPos current = rawPositions.get(i);
            BlockPos prev = (i > 0) ? rawPositions.get(i - 1) : null;
            BlockPos next = (i < rawPositions.size() - 1) ? rawPositions.get(i + 1) : null;
            double clearance = NavigationHelper.calculateClearance(snapshot, current, 2.0);
            double apexRadius = calculateApexRadius(snapshot, prev, current, next);
            MovementType type = determineMovementType(current, next);
            enrichedNodes.add(new PathNode(current, type, clearance, apexRadius));
        }
        // Safe Splice: Pass the exact start location from the request
        return new Path(request.start(), enrichedNodes, request.start().getWorld());
    }

    private MovementType determineMovementType(BlockPos current, BlockPos next) {
        if (next == null)
            return MovementType.WALK;
        boolean currentWater = isWater(current);
        boolean nextWater = isWater(next);
        if (currentWater && !nextWater && next.getY() >= current.getY()) {
            return MovementType.WATER_EXIT;
        } else if (currentWater || nextWater) {
            return MovementType.SWIM;
        }
        int yDiff = next.getY() - current.getY();
        if (yDiff > 0) {
            return MovementType.JUMP;
        } else if (yDiff < 0) {
            // Explicitly handle single-block step downs.
            // If we are moving down exactly 1 block grid position (e.g. Y=65 to Y=64),
            // this covers both:
            // 1. Cardinal Step (Stone -> Carpet): yDiff -1
            // 2. Diagonal Step (Stone -> Carpet): yDiff -1
            // In both cases, gravity handles the transition safely without a complex maneuver.
            if (yDiff == -1) {
                return MovementType.WALK;
            }
            // Otherwise, check for drop
            BlockPos ledgeCheck = new BlockPos(next.getX(), current.getY() - 1, next.getZ());
            if (NavigationHelper.isTraversable(snapshot, ledgeCheck) || NavigationHelper.isClear(snapshot, ledgeCheck)) {
                return MovementType.DROP;
            }
        }
        return MovementType.WALK;
    }

    /**
     * Calculates the "Apex Radius" for a node, which represents how tight the turn is.
     * This solves the "Corner Cutting Paradox" by enforcing tighter lookaheads at obstructions.
     */
    private double calculateApexRadius(WorldSnapshot snapshot, BlockPos prev, BlockPos curr, BlockPos next) {
        // Endpoints
        if (prev == null || next == null) {
            // Default endpoints to Loose (1.5) to allow smooth start/stop.
            return 1.5;
        }
        // Verticality (Jumps/Drops)
        // If Y-level changes, we must be tight to ensure we line up for the maneuver.
        if (prev.getY() != curr.getY() || curr.getY() != next.getY()) {
            return 0.5;
        }
        int dx1 = curr.getX() - prev.getX();
        int dz1 = curr.getZ() - prev.getZ();
        int dx2 = next.getX() - curr.getX();
        int dz2 = next.getZ() - curr.getZ();
        // Turn Detection
        if (dx1 != dx2 || dz1 != dz2) {
            // Corner Logic: Vector Subtraction
            // P_apex = P_prev + P_next - P_curr
            // Since P_next - P_curr is the exit vector, we add that to P_prev.
            // Simplified: The block "inside" the turn.
            BlockPos apexPos = new BlockPos(
                    prev.getX() + dx2,
                    curr.getY(),
                    prev.getZ() + dz2);
            // Check collision at Feet and Head
            if (NavigationHelper.hasCollision(snapshot, apexPos) ||
                    NavigationHelper.hasCollision(snapshot, apexPos.above())) {
                // Tight Turn (Obstruction detected)
                return 0.5;
            }
            // Loose Turn (Open Air)
            return 1.5;
        } else {
            // Straight Logic: Orthogonal Flank Check
            BlockPos left, right;
            if (dx1 == 0) { // Moving North/South
                left = curr.offset(-1, 0, 0);
                right = curr.offset(1, 0, 0);
            } else if (dz1 == 0) { // Moving East/West
                left = curr.offset(0, 0, -1);
                right = curr.offset(0, 0, 1);
            } else {
                // Diagonal Movement (e.g. +1, +1)
                // Flanks are the adjacent cardinals: (+1, 0) and (0, +1)
                left = curr.offset(dx1, 0, 0);
                right = curr.offset(0, 0, dz1);
            }
            boolean leftSolid = NavigationHelper.hasCollision(snapshot, left) || NavigationHelper.hasCollision(snapshot, left.above());
            boolean rightSolid = NavigationHelper.hasCollision(snapshot, right) || NavigationHelper.hasCollision(snapshot, right.above());
            if (leftSolid && rightSolid) {
                return 0.8; // Doorway/Hallway
            }
            if (leftSolid || rightSolid) {
                return 1.0; // Wall Following
            }
            return 2.0; // Open Field
        }
    }

    /**
     * Physics-Aware Start Resolution.
     * Snaps to Traversable blocks (Carpets) instead of Air above them.
     */
    private BlockPos resolveStartNode() {
        BlockPos nominalStart = request.startBlock();
        // If swimming, integer pos is fine
        if (isWater(nominalStart) && request.params().canSwim()) {
            return nominalStart;
        }
        // Sweep down to find actual support (Fence, Slab, etc.)
        Optional<BlockPos> support = NavigationHelper.findSupportingBlock(
                snapshot,
                request.getStartBounds(),
                request.params().maxFallDistance());
        if (support.isPresent()) {
            BlockPos supportPos = support.get();
            // If the support block itself is traversable (Carpet, Slab), use it as the node.
            if (NavigationHelper.isTraversable(snapshot, supportPos)) {
                return supportPos;
            }
            // Otherwise (Full Block), use the space above.
            return supportPos.above();
        }
        // If physics failed (e.g. slight misalignment), check if the integer block position is valid.
        if (NavigationHelper.isValidStandingSpot(snapshot, nominalStart)) {
            return nominalStart;
        }
        return null;
    }

    @SuppressWarnings("null")
    private List<Node> getNeighbors(Node node) {
        List<Node> neighbors = new ArrayList<>();
        BlockPos currentPos = node.pos;
        // Breath cost in water and not at the surface, accumulate breath cost.
        int nextUnderwaterTicks = node.underwaterTicks;
        if (isWater(currentPos) && !isSurface(currentPos)) {
            nextUnderwaterTicks += 20; // Approx 1 second per block traversal
        } else {
            nextUnderwaterTicks = 0; // Reset breath at surface or on land
        }
        if (nextUnderwaterTicks > MAX_BREATH_TICKS) {
            return neighbors; // Prune path: Agent would drown
        }
        // Add swimming neighbors
        if (request.params().canSwim() && isWater(currentPos)) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0)
                            continue;
                        // A valid swim destination is a water or air block with space for the head.
                        BlockPos swimPos = currentPos.offset(x, y, z);
                        if (isPassableForSwimming(swimPos) && NavigationHelper.isClear(snapshot, swimPos.above())) {
                            if (NavigationHelper.isValidStandingSpot(snapshot, swimPos))
                                continue;
                            addNeighbor(neighbors, node, swimPos, nextUnderwaterTicks);
                        }
                    }
                }
            }
        }
        // Handle water-land transitions
        if (request.params().canSwim() && isWater(currentPos)) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0)
                        continue;
                    // Check for water exit points
                    BlockPos exitPos = currentPos.offset(x, 0, z);
                    if (!isWater(exitPos) && NavigationHelper.isValidStandingSpot(snapshot, exitPos)) {
                        if (canExitWaterTo(currentPos, exitPos))
                            addNeighbor(neighbors, node, exitPos, 0);
                    }
                    BlockPos jumpExitPos = currentPos.offset(x, 1, z);
                    if (!isWater(jumpExitPos) && NavigationHelper.isValidStandingSpot(snapshot, jumpExitPos)) {
                        if (canExitWaterTo(currentPos, jumpExitPos))
                            addNeighbor(neighbors, node, jumpExitPos, 0);
                    }
                }
            }
        }
        // Walk & Climb
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                boolean isDiagonal = x != 0 && z != 0;
                // Walk
                BlockPos walkPos = currentPos.offset(x, 0, z);
                if (NavigationHelper.isValidStandingSpot(snapshot, walkPos)) {
                    if (request.params().canSwim() || snapshot.getFluidState(walkPos).isEmpty()) {
                        if (!isDiagonal || isDiagonalMoveClear(currentPos, walkPos)) {
                            addNeighbor(neighbors, node, walkPos, 0);
                        }
                    }
                } else if (isWater(walkPos) && NavigationHelper.isClear(snapshot, walkPos.above())) {
                    if (request.params().canSwim() || snapshot.getFluidState(walkPos).isEmpty()) {
                        if (!isDiagonal || isDiagonalMoveClear(currentPos, walkPos)) {
                            addNeighbor(neighbors, node, walkPos, 0);
                        }
                    }
                }
                // Climb
                BlockPos climbPos = currentPos.offset(x, 1, z);
                if (request.params().climbHeight() >= 1 && NavigationHelper.isValidStandingSpot(snapshot, climbPos)
                        && hasHeadroomForJump(currentPos)) {
                    if (!isDiagonal || isDiagonalMoveClear(currentPos, climbPos)) {
                        addNeighbor(neighbors, node, climbPos, 0);
                    }
                }
            }
        }
        // Add drop down neighbors
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                boolean isDiagonal = x != 0 && z != 0;
                BlockPos dropPos = currentPos.offset(x, 0, z);
                // Use isTraversable/isClear
                if ((NavigationHelper.isTraversable(snapshot, dropPos) || NavigationHelper.isClear(snapshot, dropPos)) &&
                        NavigationHelper.isClear(snapshot, dropPos.above())) {
                    if (!isDiagonal || isDiagonalMoveClear(currentPos, dropPos)) {
                        BlockPos fallDestination = findLandingSpot(dropPos.below(), request.params().maxFallDistance());
                        if (fallDestination != null && !fallDestination.equals(currentPos)) {
                            if (isFallPathClear(dropPos, fallDestination)) {
                                addNeighbor(neighbors, node, fallDestination, 0);
                            }
                        }
                    }
                }
            }
        }
        // Drop Straight Down
        BlockState groundState = snapshot.getBlockState(currentPos.below());
        // Check if ground is NOT traversable (i.e. it's air or fluid)
        if (!NavigationHelper.isTraversable(snapshot, currentPos.below())
                && !groundState.isFaceSturdy(EmptyBlockGetter.INSTANCE, currentPos.below(), Direction.UP)) {
            BlockPos fallDestination = findLandingSpot(currentPos.below(), request.params().maxFallDistance());
            if (fallDestination != null && !fallDestination.equals(currentPos)) {
                if (isFallPathClear(currentPos, fallDestination)) {
                    addNeighbor(neighbors, node, fallDestination, 0);
                }
            }
        }
        return neighbors;
    }

    private void addNeighbor(List<Node> neighbors, Node parent, BlockPos pos, int underwaterTicks) {
        double gCost = parent.gCost + getMoveCost(parent.pos, pos);
        double hCost = getHeuristic(pos, request.endBlock());
        neighbors.add(new Node(pos, parent, gCost, hCost, underwaterTicks));
    }

    /**
     * Uses isTraversable for corner checks to allow diagonal movement over carpets.
     */
    private boolean isDiagonalMoveClear(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        BlockPos corner1Base = from.offset(dx, 0, 0);
        BlockPos corner2Base = from.offset(0, 0, dz);
        // We check the block at the destination Y level (feet level)
        BlockPos corner1 = corner1Base.atY(to.getY());
        BlockPos corner2 = corner2Base.atY(to.getY());
        // A corner is clear if it is Traversable (Carpet/Air) OR Clear (Air).
        // It is blocked if it is a Full Block / Fence.
        boolean c1Clear = NavigationHelper.isTraversable(snapshot, corner1) || NavigationHelper.isClear(snapshot, corner1);
        boolean c2Clear = NavigationHelper.isTraversable(snapshot, corner2) || NavigationHelper.isClear(snapshot, corner2);
        // Also check head clearance at corners
        boolean c1Head = NavigationHelper.isClear(snapshot, corner1.above());
        boolean c2Head = NavigationHelper.isClear(snapshot, corner2.above());
        return c1Clear && c1Head && c2Clear && c2Head;
    }

    private boolean canExitWaterTo(BlockPos waterPos, BlockPos landPos) {
        BlockPos aboveLand = landPos.above();
        if (!NavigationHelper.isClear(snapshot, aboveLand))
            return false;
        if (landPos.getY() > waterPos.getY()) {
            return NavigationHelper.isClear(snapshot, waterPos.above());
        }
        return true;
    }

    private boolean isFallPathClear(BlockPos takeoffPos, BlockPos landingPos) {
        BlockPos.MutableBlockPos fallColumnPos = new BlockPos.MutableBlockPos(landingPos.getX(), 0, landingPos.getZ());
        for (int y = takeoffPos.getY(); y >= landingPos.getY(); y--) {
            fallColumnPos.setY(y);
            // Use isTraversable/isClear
            if (!NavigationHelper.isTraversable(snapshot, fallColumnPos) && !NavigationHelper.isClear(snapshot, fallColumnPos)) {
                return false;
            }
            if (!NavigationHelper.isClear(snapshot, fallColumnPos.above())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasHeadroomForJump(BlockPos pos) {
        return NavigationHelper.isClear(snapshot, pos.above(2));
    }

    private boolean isWater(BlockPos pos) {
        if (!request.params().canSwim())
            return false;
        BlockState state = snapshot.getBlockState(pos);
        return state != null && state.getBukkitMaterial() == Material.WATER;
    }

    private boolean isSurface(BlockPos pos) {
        return NavigationHelper.isClear(snapshot, pos.above());
    }

    private boolean isPassableForSwimming(BlockPos pos) {
        BlockState state = snapshot.getBlockState(pos);
        if (state == null)
            return false;
        Material mat = state.getBukkitMaterial();
        return mat == Material.WATER || mat.isAir();
    }

    private BlockPos findLandingSpot(BlockPos start, int searchRange) {
        BlockPos current = start;
        for (int i = 0; i < searchRange; i++) {
            if (NavigationHelper.isValidStandingSpot(snapshot, current) || isWater(current)) {
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
        double horizontalCost = (dx >= 1 && dz >= 1) ? 1.414 : (dx + dz);
        double totalCost = horizontalCost + verticalCost;
        // Additional cost modifiers
        if (isWater(to)) {
            totalCost *= 4.0; // Water is expensive
        }
        // Increase cost for water-land transitions
        if (isWater(from) && !isWater(to)) {
            totalCost *= 1.5; // Exiting water has extra cost
        }
        // UPDATED: Add small penalty for Partial Blocks to prefer Air/Flat ground
        if (NavigationHelper.isTraversable(snapshot, to) && !NavigationHelper.isClear(snapshot, to)) {
            totalCost += 0.2;
        }
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

    private static class Node implements Comparable<Node> {
        final BlockPos pos;
        final Node parent;
        final double gCost;
        final double fCost;
        final int underwaterTicks;

        Node(BlockPos pos, Node parent, double gCost, double hCost, int underwaterTicks) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.fCost = gCost + hCost;
            this.underwaterTicks = underwaterTicks;
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