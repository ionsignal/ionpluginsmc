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
    private static final int TARGET_VERTICAL_SEARCH_RANGE = 10;
    private static final double WARN_THRESHOLD_MS = 250.0;
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
        long startTime = System.nanoTime();
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
                .thenCompose(snapshot -> {
                    long endTime = System.nanoTime();
                    double durationMillis = (endTime - startTime) / 1_000_000.0;
                    LOGGER.info(String.format("Snapshot took %.3f ms to generate", durationMillis));
                    return CompletableFuture.supplyAsync(
                            () -> new AStarPathfinder(request, snapshot, token).calculatePath(),
                            IonNerrus.getInstance().getOffloadThreadExecutor());
                });
    }

    /**
     * Executes pathfinding synchronously on the calling thread using an existing snapshot.
     * Use this when running inside a `skill` that has already captured world state.
     */
    public static Optional<Path> computeImmediate(PathfindingRequest request, WorldSnapshot snapshot, ExecutionToken token) {
        return new AStarPathfinder(request, snapshot, token).calculatePath();
    }

    private Optional<Path> calculatePath() {
        // Resolve the exact starting node using physics-aware logic (Sweep -> Gravity -> Fallback)
        BlockPos actualStartPos = resolveStartNode();
        if (actualStartPos == null) {
            LOGGER.info("Could not find valid ground for start position.");
            return Optional.empty();
        }
        // Resolve the end node. If the target is mid-air (falling item), find the ground below it.
        BlockPos actualEndPos = resolveEndNode();
        // If resolution failed (e.g. over void), fallback to requested.
        if (actualEndPos == null) {
            LOGGER.info("Could not find valid ground for end position.");
            return Optional.empty();
        }
        // Setup A*
        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Node startNode = new Node(actualStartPos, null, 0, getHeuristic(actualStartPos, actualEndPos), 0);
        openSet.add(startNode);
        allNodes.put(actualStartPos, startNode);
        long startTime = System.nanoTime();
        int iterations = 0;
        int maxIterations = request.params().maxIterations();
        while (!openSet.isEmpty() && iterations++ < maxIterations) {
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
                } else {
                    LOGGER.info(String.format("Path found in %.3f ms %d iterations.", durationMillis, iterations));
                }
                return Optional.of(path);
            }
            for (Node neighbor : getNeighbors(currentNode, actualEndPos)) {
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
            double surfaceHeight = NavigationHelper.getMaxCollisionHeight(snapshot, current);
            if (surfaceHeight >= 1.0)
                surfaceHeight = 0.0;
            enrichedNodes.add(new PathNode(current, type, clearance, apexRadius, surfaceHeight));
        }
        // Safe Splice: Pass the exact start location from the request
        return new Path(request.start(), enrichedNodes, request.start().getWorld());
    }

    private MovementType determineMovementType(BlockPos current, BlockPos next) {
        boolean currentWater = isWater(current);
        // Handle Final Node (Destination)
        if (next == null) {
            if (currentWater) {
                if (NavigationHelper.isWadable(snapshot, current)) {
                    return MovementType.WADE;
                }
                return MovementType.SWIM;
            }
            return MovementType.WALK;
        }
        boolean nextWater = isWater(next);
        boolean nextWadable = NavigationHelper.isWadable(snapshot, next);
        // Water -> Land (Exit)
        if (currentWater && !nextWater && !nextWadable) {
            // If climbing out (same Y or higher)
            if (next.getY() >= current.getY()) {
                return MovementType.WATER_EXIT;
            }
        }
        // Water/Wade -> Water/Wade
        if (currentWater || nextWater) {
            // If starting on Land and ending in Water, use gravity/walking physics.
            if (!currentWater && nextWater) {
                // Mitigation B: Check Wading first
                if (NavigationHelper.isWadable(snapshot, next)) {
                    return MovementType.WADE;
                }
                int yDiff = next.getY() - current.getY();
                // If falling more than 1 block, it's a DROP.
                if (yDiff < -1)
                    return MovementType.DROP;
                // Return SWIM for deep water entry to trigger proper physics
                return MovementType.SWIM;
            }
            // If destination is specifically wadable (shallow with floor), prefer WADE
            if (nextWadable) {
                return MovementType.WADE;
            }
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
                return 0.25;
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
     * Uses NavigationHelper.resolveStartNode for robust start node detection.
     */
    private BlockPos resolveStartNode() {
        // Delegate to the new Tiered Resolution logic in NavigationHelper
        return NavigationHelper.resolveStartNode(
                snapshot,
                request.start(),
                request.entityWidth(),
                request.entityHeight(),
                request.params());
    }

    /**
     * Resolves the end node by checking if the requested target is valid.
     * If the target is in the air (e.g. falling item), it scans downwards to find the floor.
     */
    private BlockPos resolveEndNode() {
        BlockPos requested = request.endBlock();
        // Is the requested block already a valid place to stand?
        if (NavigationHelper.isValidStandingSpot(snapshot, requested)) {
            return requested;
        }
        // Is it a fluid? (Swimming target)
        if (request.params().canSwim() && isWater(requested)) {
            return requested; // eventually we'll allow water end-points (not yet)
        }
        // Is it Air/Phantom? (Falling item, flying player)
        // If so, scan downwards to find the ground.
        if (!NavigationHelper.isTraversable(snapshot, requested)) {
            // Fix: Pass params to resolveGround to allow water snapping
            BlockPos ground = NavigationHelper.resolveGround(snapshot, requested, TARGET_VERTICAL_SEARCH_RANGE, request.params());
            // Fix: Check for swimming spot validity if standing spot fails
            if (ground != null) {
                if (NavigationHelper.isValidStandingSpot(snapshot, ground)) {
                    return ground;
                }
                if (request.params().canSwim() && NavigationHelper.isValidSwimmingSpot(snapshot, ground)) {
                    return ground;
                }
            }
        }
        // Fallback: Return requested.
        // If it's invalid, A* will likely fail to find a path to it, which is the correct behavior.
        return requested;
    }

    private List<Node> getNeighbors(Node node, BlockPos targetPos) {
        List<Node> neighbors = new ArrayList<>();
        BlockPos currentPos = node.pos;
        // Breath cost in water and not at the surface, accumulate breath cost.
        int nextUnderwaterTicks = node.underwaterTicks; // underwater ticks disabled for now
        boolean isCurrentWater = isWater(currentPos);
        // Branch logic based on medium (Refactor for 6-Direction Pruning)
        if (request.params().canSwim() && isCurrentWater) {
            getWaterNeighbors(node, currentPos, targetPos, neighbors, nextUnderwaterTicks);
        } else {
            getLandNeighbors(node, currentPos, targetPos, neighbors);
        }
        return neighbors;
    }

    /**
     * Handles water movement logic.
     * Implements Mitigation M-1 (10-Neighbor Rule) and M-2 (Hybrid Wading).
     */
    @SuppressWarnings("null")
    private void getWaterNeighbors(Node node, BlockPos currentPos, BlockPos targetPos, List<Node> neighbors, int nextUnderwaterTicks) {
        // M-2: Hybrid Wading (8-Direction Horizontal + Vertical)
        // If wadable, we treat it like land walking but inside fluid to handle organic shores.
        if (NavigationHelper.isWadable(snapshot, currentPos)) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0)
                        continue;
                    BlockPos neighbor = currentPos.offset(x, 0, z);
                    // Continue Wading
                    if (NavigationHelper.isWadable(snapshot, neighbor)) {
                        if (x != 0 && z != 0 && !NavigationHelper.isDiagonalSwimClear(snapshot, currentPos, neighbor))
                            continue;
                        addNeighbor(neighbors, node, neighbor, 0, targetPos);
                        continue;
                    }
                    // Transition to Deep Swim
                    if (NavigationHelper.isPassableForSwim(snapshot, neighbor)) {
                        if (NavigationHelper.isPassableForSwim(snapshot, neighbor.above())) {
                            if (x != 0 && z != 0 && !NavigationHelper.isDiagonalSwimClear(snapshot, currentPos, neighbor))
                                continue;
                            addNeighbor(neighbors, node, neighbor, nextUnderwaterTicks, targetPos);
                        }
                    }
                    // Exit to Land (Wading Step Up)
                    if (!isWater(neighbor) && NavigationHelper.isValidStandingSpot(snapshot, neighbor)) {
                        if (canExitWaterTo(currentPos, neighbor)) {
                            addNeighbor(neighbors, node, neighbor, 0, targetPos);
                        }
                    }
                    // Cardinal Escape (Jump Exit Y+1)
                    // Solves the "Riverbank Trap" where land is 1 block higher than water bed.
                    if (x == 0 || z == 0) { // Strict Cardinal Locking to prevent diagonal clipping
                        BlockPos jumpExit = neighbor.above(); // The block at Y+1
                        if (!isWater(jumpExit) && NavigationHelper.isValidStandingSpot(snapshot, jumpExit)) {
                            if (canExitWaterTo(currentPos, jumpExit)) {
                                addNeighbor(neighbors, node, jumpExit, 0, targetPos);
                            }
                        }
                    }
                }
            }
            return;
        }
        // M-1: Deep Swim (10-Neighbor Rule: 6 Cardinals + 4 Jump Exits)
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = currentPos.relative(dir);
            // 1. Swim (Cardinal)
            if (NavigationHelper.isPassableForSwim(snapshot, neighbor)) {
                // Prevent swimming into Air (Porpoising) unless it's a surface transition
                if (!snapshot.getBlockState(neighbor).isAir()) {
                    // Check headroom
                    if (NavigationHelper.isPassableForSwim(snapshot, neighbor.above())) {
                        addNeighbor(neighbors, node, neighbor, nextUnderwaterTicks, targetPos);
                    }
                }
            }
            // 2. Exit to Land (Horizontal Cardinals only)
            if (dir.getAxis().isHorizontal()) {
                // Direct Step Out
                if (!isWater(neighbor) && NavigationHelper.isValidStandingSpot(snapshot, neighbor)) {
                    if (canExitWaterTo(currentPos, neighbor)) {
                        addNeighbor(neighbors, node, neighbor, 0, targetPos);
                    }
                }
                // Jump Exit (Forward + Up) - Solves R-1 Shoreline Wall Trap
                BlockPos jumpExit = neighbor.above();
                if (!isWater(jumpExit) && NavigationHelper.isValidStandingSpot(snapshot, jumpExit)) {
                    if (canExitWaterTo(currentPos, jumpExit)) {
                        addNeighbor(neighbors, node, jumpExit, 0, targetPos);
                    }
                }
            }
        }
    }

    /**
     * Handles land movement logic (Walk, Climb, Drop).
     * Extracted from original getNeighbors for encapsulation.
     */
    @SuppressWarnings("null")
    private void getLandNeighbors(Node node, BlockPos currentPos, BlockPos targetPos, List<Node> neighbors) {
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
                            addNeighbor(neighbors, node, walkPos, 0, targetPos);
                        }
                    }
                }
                // Use isValidSwimmingSpot for robustness
                else if (NavigationHelper.isValidSwimmingSpot(snapshot, walkPos)) {
                    if (request.params().canSwim()) { // isWater check implies this usually but explicit is good
                        if (!isDiagonal || isDiagonalMoveClear(currentPos, walkPos)) {
                            addNeighbor(neighbors, node, walkPos, 0, targetPos);
                        }
                    }
                }
                // Climb
                BlockPos climbPos = currentPos.offset(x, 1, z);
                if (request.params().climbHeight() >= 1 && NavigationHelper.isValidStandingSpot(snapshot, climbPos)
                        && hasHeadroomForJump(currentPos)) {
                    if (!isDiagonal || isDiagonalMoveClear(currentPos, climbPos)) {
                        addNeighbor(neighbors, node, climbPos, 0, targetPos);
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
                // Allow dropping through Water if we can swim
                boolean isDropStartPassable = NavigationHelper.isTraversable(snapshot, dropPos) ||
                        NavigationHelper.isClear(snapshot, dropPos) ||
                        isWater(dropPos);
                if (isDropStartPassable && NavigationHelper.isClear(snapshot, dropPos.above())) {
                    if (!isDiagonal || isDiagonalMoveClear(currentPos, dropPos)) {
                        BlockPos fallDestination = findLandingSpot(dropPos.below(), request.params().maxFallDistance());
                        if (fallDestination != null && !fallDestination.equals(currentPos)) {
                            if (isFallPathClear(dropPos, fallDestination)) {
                                addNeighbor(neighbors, node, fallDestination, 0, targetPos);
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
                    addNeighbor(neighbors, node, fallDestination, 0, targetPos);
                }
            }
        }
    }

    private void addNeighbor(List<Node> neighbors, Node parent, BlockPos pos, int underwaterTicks, BlockPos targetPos) {
        double gCost = parent.gCost + getMoveCost(parent.pos, pos);
        double hCost = getHeuristic(pos, targetPos);
        neighbors.add(new Node(pos, parent, gCost, hCost, underwaterTicks));
    }

    /**
     * Uses BlockClassification to prevent clipping through SUPPORTING blocks.
     */
    private boolean isDiagonalMoveClear(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        BlockPos corner1Base = from.offset(dx, 0, 0);
        BlockPos corner2Base = from.offset(0, 0, dz);
        // We check the block at the destination Y level (feet level)
        BlockPos corner1 = corner1Base.atY(to.getY());
        BlockPos corner2 = corner2Base.atY(to.getY());
        // Check if corners are blocked by SOLID or SUPPORTING blocks
        BlockClassification c1Type = BlockClassification.classify(snapshot, corner1);
        BlockClassification c2Type = BlockClassification.classify(snapshot, corner2);
        boolean c1Blocked = (c1Type == BlockClassification.SOLID || c1Type == BlockClassification.SUPPORTING);
        boolean c2Blocked = (c2Type == BlockClassification.SOLID || c2Type == BlockClassification.SUPPORTING);
        // Also check head clearance at corners
        boolean c1Head = NavigationHelper.isClear(snapshot, corner1.above());
        boolean c2Head = NavigationHelper.isClear(snapshot, corner2.above());
        return !c1Blocked && c1Head && !c2Blocked && c2Head;
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
            // Context-Aware Fall Check.
            // 1. Traversable (Carpet/Slab) -> OK
            // 2. Clear (Air/Grass) -> OK
            // 3. Water (if canSwim) -> OK
            boolean isPassable = NavigationHelper.isTraversable(snapshot, fallColumnPos) ||
                    NavigationHelper.isClear(snapshot, fallColumnPos) ||
                    isWater(fallColumnPos);
            if (!isPassable) {
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
        return BlockClassification.classify(snapshot, pos) == BlockClassification.FLUID;
    }

    private boolean isSurface(BlockPos pos) {
        return NavigationHelper.isClear(snapshot, pos.above());
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
        boolean toWater = isWater(to);
        boolean fromWater = isWater(from);
        if (toWater) {
            if (NavigationHelper.isWadable(snapshot, to)) {
                totalCost *= 1.2; // Wading is slightly harder than walking
            } else {
                // Tiered Water Costs:
                // Surface (1.5) vs Deep (2.5) creates natural pressure to surface.
                if (isSurface(to)) {
                    totalCost *= 1.5; // Surface Swim (The Highway)
                } else {
                    totalCost *= 2.5; // Deep Swim (The Pressure)
                }
            }
        }
        // Water-Land Transitions
        if (fromWater && !toWater) {
            totalCost *= 1.5; // Exiting effort
        }
        // Add small penalty for Partial Blocks to prefer Air/Flat ground
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
        // Weighted A* (1.6) to bias exploration towards the target ("The Shove")
        // This helps punch through the resistance of water costs.
        return (Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ())) * 1.6;
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