package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult.MovementType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.bukkit.Material;
import org.bukkit.Tag;

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
            BlockPos ledgeCheck = new BlockPos(next.getX(), current.getY() - 1, next.getZ());
            if (NavigationHelper.isPassable(snapshot, ledgeCheck)) {
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
     * Uses the AABB from the request to find the actual block supporting the entity.
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
        // If found, return the space ABOVE the support.
        return support.map(BlockPos::above).orElse(null);
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
                        if (isPassableForSwimming(swimPos) && isAirOrPassable(snapshot.getBlockState(swimPos.above()))) {
                            // If the swim destination is also a valid standing position (e.g. shallow water),
                            // let the more specific walking logic handle it to ensure correct costs.
                            if (isValidStandingPos(swimPos))
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
                    if (!isWater(exitPos) && isValidStandingPos(exitPos)) {
                        // Can we exit water here?
                        if (canExitWaterTo(currentPos, exitPos)) {
                            addNeighbor(neighbors, node, exitPos, 0); // Reset breath on land
                        }
                    }
                    // Check for jumping out of water onto higher ground
                    BlockPos jumpExitPos = currentPos.offset(x, 1, z);
                    if (!isWater(jumpExitPos) && isValidStandingPos(jumpExitPos)) {
                        if (canExitWaterTo(currentPos, jumpExitPos)) {
                            addNeighbor(neighbors, node, jumpExitPos, 0); // Reset breath on land
                        }
                    }
                    // Check for lily pads and similar surfaces
                    if (isWaterWithSurface(exitPos)) {
                        addNeighbor(neighbors, node, exitPos.above(), 0);
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
                        addNeighbor(neighbors, node, walkPos, 0);
                    }
                } else if (isWater(walkPos) && isAirOrPassable(snapshot.getBlockState(walkPos.above()))) {
                    // This handles walking from land directly into a water block.
                    // Also check clearance when moving diagonally into water.
                    if (!isDiagonal || isDiagonalMoveClear(currentPos, walkPos)) {
                        addNeighbor(neighbors, node, walkPos, 0);
                    }
                }
                // Climb
                BlockPos climbPos = currentPos.offset(x, 1, z);
                if (request.params().climbHeight() >= 1 && isValidStandingPos(climbPos) && hasHeadroomForJump(currentPos)) {
                    // For diagonal climbs, perform an additional clearance check.
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
                if (isAirOrPassable(snapshot.getBlockState(dropPos)) && isAirOrPassable(snapshot.getBlockState(dropPos.above()))) {
                    // Added check to prevent clipping corners when initiating a diagonal fall.
                    // This ensures the move from the current position to the edge we fall from is physically possible.
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
        // Add drop straight down neighbors
        BlockState groundState = snapshot.getBlockState(currentPos.below());
        if (groundState != null && !groundState.isFaceSturdy(EmptyBlockGetter.INSTANCE, currentPos.below(), Direction.UP)) {
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

    private boolean isDiagonalMoveClear(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        BlockPos corner1Base = from.offset(dx, 0, 0);
        BlockPos corner2Base = from.offset(0, 0, dz);
        BlockPos corner1AtDestY = corner1Base.atY(to.getY());
        BlockPos corner2AtDestY = corner2Base.atY(to.getY());
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

    @SuppressWarnings("null")
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

    @SuppressWarnings("null")
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

    @SuppressWarnings("null")
    private boolean isWaterWithSurface(BlockPos pos) {
        if (!isWater(pos))
            return false;
        BlockState above = snapshot.getBlockState(pos.above());
        if (above == null)
            return false;
        Material mat = above.getBukkitMaterial();
        return mat == Material.LILY_PAD || Tag.WOOL_CARPETS.isTagged(mat)
                || (mat.isSolid() && above.getCollisionShape(EmptyBlockGetter.INSTANCE, pos.above()).isEmpty());
    }

    private boolean isFallPathClear(BlockPos takeoffPos, BlockPos landingPos) {
        // The landing spot itself is already validated by `isValidStandingPos`.
        // We need to check the column of air the entity falls through.
        // The fall starts at the takeoff Y-level and at the landing X/Z coordinates.
        // Iterate from the Y-level of the takeoff position down to the Y-level of the landing position.
        // We check the full 1x2 space at each Y-level of the fall.
        BlockPos.MutableBlockPos fallColumnPos = new BlockPos.MutableBlockPos(landingPos.getX(), 0, landingPos.getZ());
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

    @SuppressWarnings("null")
    private boolean isValidStandingPos(BlockPos pos) {
        BlockState ground = snapshot.getBlockState(pos.below());
        if (ground == null) {
            return false;
        }
        boolean canStandOn = ground.isFaceSturdy(EmptyBlockGetter.INSTANCE, pos.below(), Direction.UP)
                || Tag.LEAVES.isTagged(ground.getBukkitMaterial());
        if (!canStandOn) {
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
        // To jump, the block at `pos.above(2)` must be clear
        BlockState twoAbove = snapshot.getBlockState(pos.above(2));
        return twoAbove != null && isAirOrPassable(twoAbove);
    }

    private boolean isWater(BlockPos pos) {
        if (!request.params().canSwim())
            return false;
        BlockState state = snapshot.getBlockState(pos);
        return state != null && state.getBukkitMaterial() == Material.WATER;
    }

    // Added helper for breath logic
    private boolean isSurface(BlockPos pos) {
        BlockState above = snapshot.getBlockState(pos.above());
        return above != null && above.getBukkitMaterial().isAir();
    }

    private boolean isPassableForSwimming(BlockPos pos) {
        BlockState state = snapshot.getBlockState(pos);
        if (state == null)
            return false;
        Material mat = state.getBukkitMaterial();
        return mat == Material.WATER || mat.isAir();
    }

    @SuppressWarnings("null")
    private boolean isAirOrPassable(BlockState state) {
        if (state == null)
            return false;
        Material mat = state.getBukkitMaterial();
        if (Tag.LEAVES.isTagged(mat)) {
            return false;
        }
        if (mat.isAir())
            return true;
        if (mat == Material.WATER && request.params().canSwim())
            return true;
        // Use collision shape as the primary check. This correctly handles fences, walls, leaves, etc.
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
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