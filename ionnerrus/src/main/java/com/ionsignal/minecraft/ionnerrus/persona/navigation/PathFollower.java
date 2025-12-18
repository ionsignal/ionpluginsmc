package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult.MovementType;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Simplified Path Follower.
 * Decouples Spline Projection (Localization) from Steering Logic (Decision).
 * Implements "Proximity Clamping" to handle maneuvers.
 */
public class PathFollower {
    // Tuning Constants
    private static final double LOOKAHEAD_DISTANCE = 1.5;
    private static final double PROJECTION_STEP_SIZE = 0.5;
    private static final double PROJECTION_SEARCH_WINDOW = 3.0;
    private static final double REACQUISITION_WINDOW = 1.0;
    private static final double MANEUVER_THRESHOLD = 0.5;
    private static final double COMPLETION_THRESHOLD = 0.36;

    private final Path path;

    // State
    private double currentDist;
    private boolean isFinished;

    private DebugVisualizer.Rabbit debugRabbit;
    private NamedTextColor debugRabbitColor = NamedTextColor.RED;

    public PathFollower(Path path) {
        this.path = path;
        this.currentDist = 0.0;
        this.isFinished = false;
    }

    /**
     * Updates the internal state (Projection & Completion) based on agent position.
     * Does NOT perform failure/deviation checks.
     */
    public void updateState(Location currentPos) {
        if (path.isEmpty()) {
            isFinished = true;
            return;
        }
        // Update Projection
        updateProjection(currentPos);
        // Check Completion
        Location finalPoint = path.getPointAtDistance(path.getLength());
        // Compensate for partial block vertical offset using precise metadata
        double completionRadius = COMPLETION_THRESHOLD;
        PathNode finalNode = path.getNodeAtDistance(path.getLength());
        if (finalNode != null && finalNode.type() == MovementType.WALK) {
            completionRadius += finalNode.surfaceOffset();
        }
        double adjustedThresholdSq = completionRadius * completionRadius;
        if (currentPos.distanceSquared(finalPoint) < adjustedThresholdSq) {
            isFinished = true;
        }
    }

    /**
     * Calculates the steering intent.
     * Implements the Approach vs. Action state machine.
     */
    public SteeringResult getAndVisualizeSteering(Location currentPos) {
        SteeringResult result;
        result = getSteering(currentPos);
        if (debugRabbit == null) {
            debugRabbit = new DebugVisualizer.Rabbit(result.target(), debugRabbitColor);
        }
        debugRabbit.move(result.target());
        return result;
    }

    // Completely rewritten to prioritize Physical Node Lookup over Spline Projection
    public SteeringResult getSteering(Location currentPos) {
        if (path.isEmpty() || isFinished) {
            return new SteeringResult(currentPos, MovementType.WALK);
        }
        // Get physical node to check for maneuver
        PathNode physicalNode = getPhysicalNode(currentPos);
        // If we are physically standing on a maneuver block we execute it
        if (physicalNode != null && isManeuver(physicalNode.type())) {
            PathNode destNode = path.getNextNode(physicalNode);
            return new SteeringResult(destNode.toLocation(path.getWorld()), physicalNode.type());
        }
        // If we are walking/swimming, or off-path, we look ahead using the spline
        double scanEnd = Math.min(currentDist + LOOKAHEAD_DISTANCE, path.getLength());
        // Check if a maneuver is coming up in the lookahead window
        double nextNodeDist = path.getNextNodeDistance(currentDist);
        if (nextNodeDist <= scanEnd) {
            // We use getNodeAtDistance here to peek at the upcoming node on the spline
            PathNode nextNode = path.getNodeAtDistance(nextNodeDist);
            if (nextNode != null && isManeuver(nextNode.type())) {
                Location maneuverLoc = nextNode.toLocation(path.getWorld());
                double distToManeuver = currentPos.distance(maneuverLoc);
                // Proximity Check (Mitigates "Corner Cutting" Risk)
                if (distToManeuver < MANEUVER_THRESHOLD) {
                    // Close enough to trigger, even if not physically on the block
                    PathNode destNode = path.getNextNode(nextNode);
                    return new SteeringResult(destNode.toLocation(path.getWorld()), nextNode.type());
                } else {
                    // Walk to the maneuver start point
                    MovementType approachType = isWater(currentPos) ? MovementType.SWIM : MovementType.WALK;
                    return new SteeringResult(maneuverLoc, approachType);
                }
            }
        }
        // Cruise Mode (Standard Path Following)
        Location target = path.getPointAtDistance(scanEnd);
        MovementType type = isWater(currentPos) ? MovementType.SWIM : MovementType.WALK;
        return new SteeringResult(target, type);
    }

    // Helper to check if a movement type is a complex maneuver
    private boolean isManeuver(MovementType type) {
        return type != MovementType.WALK && type != MovementType.SWIM && type != MovementType.WADE;
    }

    // Fuzzy Vertical Lookup to handle Slabs, Carpets, and Jumping
    private PathNode getPhysicalNode(Location loc) {
        BlockPos pos = new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        PathNode node = path.getNodeAt(pos);
        // If not found, and we are standing in something passable (Air/Grass), check below.
        // This handles jumping, hovering, or slight vertical desyncs.
        if (node == null) {
            ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
            if (NavigationHelper.isPassable(level, pos)) {
                node = path.getNodeAt(pos.below());
            }
        }
        return node;
    }

    /**
     * Forces the internal path cursor to a specific distance.
     * Used by Navigator to sync state after a maneuver completes.
     */
    public void snapToSegment(double distance) {
        this.currentDist = Math.min(Math.max(0, distance), path.getLength());
    }

    /**
     * Projects the agent's position onto the path spline using a sliding window.
     */
    private void updateProjection(Location agentPos) {
        double bestDist = currentDist;
        double minDeviation = Double.MAX_VALUE;
        // Windowed Search
        double startSearch = Math.max(0, currentDist - REACQUISITION_WINDOW);
        double endSearch = Math.min(path.getLength(), currentDist + PROJECTION_SEARCH_WINDOW);
        for (double d = startSearch; d <= endSearch; d += PROJECTION_STEP_SIZE) {
            Location point = path.getPointAtDistance(d);
            double deviation = point.distanceSquared(agentPos);
            if (deviation < minDeviation) {
                minDeviation = deviation;
                bestDist = d;
            }
        }
        // Check exact end of path to fix quantization errors
        Location endPoint = path.getPointAtDistance(path.getLength());
        double endDeviation = endPoint.distanceSquared(agentPos);
        if (endDeviation < minDeviation) {
            minDeviation = endDeviation;
            bestDist = path.getLength();
        }
        // Teleport/Knockback detection (Coarse Scan fallback)
        boolean atEdge = (bestDist >= endSearch - PROJECTION_STEP_SIZE) || (bestDist <= startSearch + PROJECTION_STEP_SIZE);
        if (atEdge && minDeviation > 16.0) {
            bestDist = fullScan(agentPos);
        }
        this.currentDist = bestDist;
    }

    private double fullScan(Location agentPos) {
        double bestDist = 0.0;
        double minDeviation = Double.MAX_VALUE;
        for (double d = 0; d <= path.getLength(); d += 1.0) {
            Location point = path.getPointAtDistance(d);
            double deviation = point.distanceSquared(agentPos);
            if (deviation < minDeviation) {
                minDeviation = deviation;
                bestDist = d;
            }
        }
        return bestDist;
    }

    /**
     * Checks if the agent is currently in a fluid using the centralized BlockClassification logic.
     * This correctly handles waterlogged blocks, bubble columns, and flowing water.
     */
    private boolean isWater(Location loc) {
        ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
        BlockPos pos = new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return BlockClassification.classify(level, pos) == BlockClassification.FLUID;
    }

    public boolean isFinished(Location currentPos) {
        return isFinished;
    }

    public boolean isOffPath() {
        return false;
    }

    public double getCurrentDist() {
        return currentDist;
    }

    /**
     * Cleans up debug entities. Must be called when the path follower is discarded.
     */
    public void cleanup() {
        if (debugRabbit != null) {
            debugRabbit.remove();
            debugRabbit = null;
        }
    }
}