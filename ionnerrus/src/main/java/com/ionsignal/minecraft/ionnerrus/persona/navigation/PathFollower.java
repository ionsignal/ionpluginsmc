package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult.MovementType;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Optional;

/**
 * Variable Rabbit Path Follower implementation that projects the agent's position onto the path
 * spline to find `currentDist`. We check deviation (Tether Snap) using the node's `clearanceRadius`
 * and `apex`, then calculate a dynamic lookahead point based on traversability.
 */
public class PathFollower {
    // Tuning Constants
    private static final boolean SHOW_RABBIT = true;
    private static final double BASE_LOOKAHEAD = 1.2; // Base lookahead in meters
    private static final double MAX_LOOKAHEAD = 5.0; // Max lookahead for open areas
    private static final double TETHER_SLACK = 0.8; // Extra forgiveness before snapping tether
    private static final double PROJECTION_STEP_SIZE = 0.5; // Size of our projection steps
    private static final double PROJECTION_SEARCH_WINDOW = 5.0; // How far forward/back to search for projection
    private static final double COMPLETION_THRESHOLD = 0.36; // Proximity to be considered done
    private static final double COMPLETION_THRESHOLD_SQUARED = COMPLETION_THRESHOLD * COMPLETION_THRESHOLD;

    private final Path path;

    // State
    private double currentDist;
    private double minSearchDist;
    private boolean isOffPath;
    private boolean isFinished;
    private DebugVisualizer.Rabbit debugRabbit;
    private NamedTextColor debugRabbitColor = NamedTextColor.RED;

    public PathFollower(Path path) {
        this.path = path;
        this.currentDist = 0.0;
        this.minSearchDist = 0.0;
        this.isOffPath = false;
        this.isFinished = false;
    }

    /**
     * Updates the internal state of the follower (Projection, Completion, Tether) based on the agent's
     * current position. This separates "Where am I?" from "Where should I go?".
     *
     * @param currentPos
     *            The agent's current location.
     */
    public void updateState(Location currentPos) {
        if (path.isEmpty()) {
            isFinished = true;
            return;
        }
        // Update our spline projection
        updateProjection(currentPos);
        // Check Completion
        Location finalPoint = path.getPointAtDistance(path.getLength());
        if (currentPos.distanceSquared(finalPoint) < COMPLETION_THRESHOLD_SQUARED) {
            isFinished = true;
        }
        // Tether Check (lost)
        // Allowed deviation = clearanceRadius + slack
        PathNode currentNode = path.getNodeAtDistance(currentDist);
        if (currentNode != null) {
            double allowedDeviation = currentNode.clearanceRadius() + TETHER_SLACK;
            Location projectedPoint = path.getPointAtDistance(currentDist);
            double actualDeviation = currentPos.distance(projectedPoint);
            // Update off-path status based on current deviation
            this.isOffPath = actualDeviation > allowedDeviation;
        }
    }

    /**
     * Calculates the steering intent based on the current internal state.
     * Requires updateState() to be called first (or use calculateSteering wrapper).
     *
     * @param currentPos
     *            The agent's current location (used for maneuver bounds checks).
     * @return The calculated steering result.
     */
    public SteeringResult getSteering(Location currentPos) {
        if (path.isEmpty()) {
            return new SteeringResult(currentPos, MovementType.WALK);
        }
        if (isFinished) {
            Location finalPoint = path.getPointAtDistance(path.getLength());
            return new SteeringResult(finalPoint, MovementType.WALK);
        }
        if (isOffPath) {
            // Return a result targeting the projection, but Navigator should catch the isOffPath flag
            Location projectedPoint = path.getPointAtDistance(currentDist);
            return new SteeringResult(projectedPoint, MovementType.WALK);
        }
        // Retrieve Metadata for current segment
        PathNode currentNode = path.getNodeAtDistance(currentDist);
        if (currentNode == null) {
            return new SteeringResult(currentPos, MovementType.WALK);
        }
        // 1. Calculate Ideal Lookahead (Speed based on Clearance)
        double idealLookahead = BASE_LOOKAHEAD * (currentNode.clearanceRadius() / 0.6);
        idealLookahead = Math.min(idealLookahead, MAX_LOOKAHEAD);
        idealLookahead = Math.max(idealLookahead, 0.5); // Minimum lookahead
        // 2. Calculate Geometric Constraints (Braking based on Apex)
        // Constraint A: The immediate upcoming node (End of current segment)
        double nextNodeDist = path.getNextNodeDistance(currentDist);
        PathNode nextNode = path.getNodeAtDistance(nextNodeDist);
        double constraint1 = nextNodeDist + (nextNode != null ? nextNode.apexRadius() : 100.0);
        // Constraint B: The node after that (Lookahead for short segments)
        double constraint2 = Double.MAX_VALUE;
        // Only check further if we are reasonably close to the first turn
        if (nextNodeDist - currentDist < 2.0) {
            double nextNextDist = path.getNextNodeDistance(nextNodeDist + 0.01);
            if (nextNextDist > nextNodeDist) {
                PathNode nextNextNode = path.getNodeAtDistance(nextNextDist);
                constraint2 = nextNextDist + (nextNextNode != null ? nextNextNode.apexRadius() : 100.0);
            }
        }
        // The absolute furthest distance along the path we are allowed to look
        double maxPathDist = Math.min(constraint1, constraint2);
        double maxSafeLookahead = maxPathDist - currentDist;
        // Ensure we don't stutter by enforcing a minimum vector length
        maxSafeLookahead = Math.max(maxSafeLookahead, 0.5);
        // Effective lookahead is the minimum of Ideal (Speed) and Safe (Geometry)
        double effectiveLookahead = Math.min(idealLookahead, maxSafeLookahead);
        // Determine Target & Movement Type
        MovementType type = MovementType.WALK;
        double targetDist = Math.min(currentDist + effectiveLookahead, path.getLength());
        Location targetLoc = path.getPointAtDistance(targetDist);
        PathNode immediateNext = path.getNodeAtDistance(currentDist + 0.1);
        if (immediateNext != null && immediateNext.type() != MovementType.WALK) {
            // Calculate the destination of this maneuver segment
            Location maneuverDest = path.getSegmentDestination(currentDist + 0.1);
            // Ask the MovementType if we are already inside the completion zone
            if (maneuverDest != null && immediateNext.type().isInsideCompletionBounds(currentPos, maneuverDest)) {
                // We are physically at the destination of the jump/drop.
                // Therefore, the command to Jump/Drop is obsolete.
                // Push the target PAST the maneuver node to the destination.
                // This prevents the agent from getting "stuck" with a backward/stationary target.
                double segmentEnd = path.getNextNodeDistance(currentDist + 0.1);
                // Add small epsilon to ensure we're clearly past the node
                targetDist = segmentEnd + 0.1;
                // Update targetLoc since we're not entering the JUMP/DROP projection block below
                targetLoc = path.getPointAtDistance(targetDist);
            } else {
                type = immediateNext.type();
                // Target the maneuver node so downstream projection logic can work
                targetLoc = maneuverDest;
            }
        }
        // Calculate Exit Heading (Lookahead)
        // Find the node AFTER the target to determine where we go next.
        Optional<Vector> exitHeading = Optional.empty();
        if (targetLoc != null) {
            // Look 2 meters past the target to get the general trend of the path
            double lookPastDist = Math.min(targetDist + 2.0, path.getLength());
            if (lookPastDist > targetDist + 0.1) {
                Location nextLoc = path.getPointAtDistance(lookPastDist);
                exitHeading = Optional.of(nextLoc.toVector().subtract(targetLoc.toVector()).normalize());
            }
        }
        // Inject Visualization Logic right before returning
        if (SHOW_RABBIT) {
            if (debugRabbit == null) {
                // Lazy initialization on first tick
                debugRabbit = new DebugVisualizer.Rabbit(targetLoc, debugRabbitColor);
            }
            // Move the rabbit to the calculated target
            debugRabbit.move(targetLoc);
        }
        return new SteeringResult(targetLoc, type, exitHeading);
    }

    /**
     * Legacy wrapper to maintain compatibility with Navigator until Phase 3.
     * Combines state update and steering calculation.
     */
    public SteeringResult calculateSteering(Location currentPos) {
        updateState(currentPos);
        return getSteering(currentPos);
    }

    /**
     * Forces the internal path cursor to a specific distance.
     * Used by the Navigator to sync state when a Maneuver completes successfully.
     *
     * @param distance
     *            The distance along the path to snap to.
     */
    public void snapToSegment(double distance) {
        this.currentDist = Math.min(Math.max(0, distance), path.getLength());
        // Update the ratchet. We never search before this point again.
        this.minSearchDist = this.currentDist;
        // Clear off-path status since we are forcibly snapping to a valid point
        this.isOffPath = false;
    }

    /**
     * Projects the agent's position onto the path spline to find the closest point `t`.
     * Uses a sliding window search around `currentDist` for efficiency.
     */
    private void updateProjection(Location agentPos) {
        double bestDist = currentDist;
        double minDeviation = Double.MAX_VALUE;
        double startSearch = Math.max(minSearchDist, currentDist - 1.0);
        double endSearch = Math.min(path.getLength(), currentDist + PROJECTION_SEARCH_WINDOW);
        for (double d = startSearch; d <= endSearch; d += PROJECTION_STEP_SIZE) {
            Location point = path.getPointAtDistance(d);
            double deviation = point.distanceSquared(agentPos);
            if (deviation < minDeviation) {
                minDeviation = deviation;
                bestDist = d;
            }
        }
        // The loop above steps by 0.5. If path length is 10.25, it checks 10.0 and stops.
        // We explicitly check the exact end of the path to fix quantization errors.
        Location endPoint = path.getPointAtDistance(path.getLength());
        double endDeviation = endPoint.distanceSquared(agentPos);
        if (endDeviation < minDeviation) {
            minDeviation = endDeviation;
            bestDist = path.getLength();
        }
        // Teleport/Knockback detection
        // If the best match is at the very edge of our search window AND deviation is high (> 4 blocks),
        // we likely lost the track due to external movement. Perform a full scan.
        boolean atEdge = (bestDist >= endSearch - PROJECTION_STEP_SIZE) || (bestDist <= startSearch + PROJECTION_STEP_SIZE);
        if (atEdge && minDeviation > 16.0) {
            bestDist = fullScan(agentPos);
        }
        // If we moved forward, update.
        // We generally don't want to move `currentDist` backwards unless the agent was knocked back
        // significantly.
        // But the search window handles local knockback.
        this.currentDist = bestDist;
    }

    /**
     * Performs a coarse O(N) scan over the entire path to re-acquire the agent's position.
     * Used when the sliding window loses track of the agent.
     */
    private double fullScan(Location agentPos) {
        double bestDist = minSearchDist;
        double minDeviation = Double.MAX_VALUE;
        // Coarse scan over entire path (1m resolution)
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

    public boolean isFinished(Location currentPos) {
        return isFinished;
    }

    public boolean isOffPath() {
        return isOffPath;
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
        this.minSearchDist = 0.0;
    }
}