package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult.MovementType;

import org.bukkit.Location;

/**
 * Variable Rabbit Path Follower implementation that projects the agent's position onto the path
 * spline to find `currentDist`. We check deviation (Tether Snap) using the node's `clearanceRadius`
 * and then calculate a dynamic lookahead point based on traversability.
 */
public class PathFollower {
    // Tuning Constants
    private static final double BASE_LOOKAHEAD = 1.5; // Base lookahead in meters
    private static final double MAX_LOOKAHEAD = 5.0; // Max lookahead for open areas
    private static final double TETHER_SLACK = 0.5; // Extra forgiveness before snapping tether
    private static final double SEARCH_WINDOW = 5.0; // How far forward/back to search for projection
    private static final double MIN_CORRIDOR_CLEARANCE = 0.8; // Prevents cutting corners in tunnels

    private final Path path;

    // State
    private double currentDist; // Distance along path (meters)
    private boolean isOffPath;
    private boolean isFinished;

    public PathFollower(Path path) {
        this.path = path;
        this.currentDist = 0.0;
        this.isOffPath = false;
        this.isFinished = false;
    }

    public SteeringResult calculateSteering(Location currentPos) {
        if (path.isEmpty()) {
            isFinished = true;
            return new SteeringResult(currentPos, MovementType.WALK);
        }
        // Update Projection (Where am I on the spline?)
        updateProjection(currentPos);
        // Check Completion
        if (currentDist >= path.getLength() - 0.5) {
            isFinished = true;
            return new SteeringResult(path.getPointAtDistance(path.getLength()), MovementType.WALK);
        }
        // Retrieve Metadata for current segment
        PathNode currentNode = path.getNodeAtDistance(currentDist);
        if (currentNode == null) {
            // Should not happen if logic is correct
            return new SteeringResult(currentPos, MovementType.WALK);
        }
        // Tether Check (lost)
        // Allowed deviation = clearanceRadius + slack
        double allowedDeviation = currentNode.clearanceRadius() + TETHER_SLACK;
        Location projectedPoint = path.getPointAtDistance(currentDist);
        double actualDeviation = currentPos.distance(projectedPoint);
        if (actualDeviation > allowedDeviation) {
            this.isOffPath = true;
            // Return a result, but Navigator should catch the isOffPath flag
            return new SteeringResult(projectedPoint, MovementType.WALK);
        }
        // Calculate Dynamic Lookahead (The Rabbit)
        // Wider clearance = Further lookahead = Smoother movement
        // Narrow clearance = Shorter lookahead = Tighter cornering
        double idealLookahead = BASE_LOOKAHEAD * (currentNode.clearanceRadius() / 0.6);
        idealLookahead = Math.min(idealLookahead, MAX_LOOKAHEAD);
        idealLookahead = Math.max(idealLookahead, 0.5); // Minimum lookahead
        // Scan ahead to see if we are approaching a choke point (narrow corridor) or a special maneuver.
        // If so, shorten the lookahead to ensure we line up correctly.
        double effectiveLookahead = idealLookahead;
        double scanStep = 0.5; // Check resolution (half-block)
        for (double d = scanStep; d <= idealLookahead; d += scanStep) {
            double checkDist = currentDist + d;
            if (checkDist >= path.getLength()) {
                break;
            }
            PathNode nodeAhead = path.getNodeAtDistance(checkDist);
            // Constraint 1: Choke Points
            // If the path ahead narrows significantly (e.g., < 0.8m clearance), we must not look past it.
            // We allow a small buffer so we enter the choke point straight on.
            if (nodeAhead.clearanceRadius() < MIN_CORRIDOR_CLEARANCE) {
                effectiveLookahead = d;
                break;
            }
            // Constraint 2: Maneuver Preparation
            // If a Jump or Drop is coming up, we treat it as a "hard stop" for steering.
            // We want to steer exactly TO the jump point, not past it.
            if (nodeAhead.type() != MovementType.WALK) {
                effectiveLookahead = d;
                break;
            }
        }
        // Determine Target & Movement Type
        // Default to WALK. We only switch to JUMP/DROP if we are physically close to the node
        // Edge Case: Immediate Jump/Drop
        // Even if our lookahead is clamped, if the VERY NEXT node is a maneuver, we must execute it.
        // This handles the case where we are standing right at the edge.
        MovementType type = MovementType.WALK;
        double targetDist = Math.min(currentDist + effectiveLookahead, path.getLength());
        Location targetLoc = path.getPointAtDistance(targetDist);
        PathNode immediateNext = path.getNodeAtDistance(currentDist + 0.1);
        if (immediateNext != null && immediateNext.type() != MovementType.WALK) {
            type = immediateNext.type();
            // If we switched type due to proximity, ensure we target the maneuver node
            // so the logic below can project through it.
            targetDist = currentDist + 0.1;
        }
        // If the target node is a transition (Jump/Drop), we must not stop AT the node.
        // We must target the destination of the maneuver (the landing spot).
        // We project the target distance forward by a safe margin (1.5m) to ensure the
        // vector points THROUGH the takeoff point towards the landing.
        if (type == MovementType.JUMP || type == MovementType.DROP) {
            double projectionDist = Math.min(targetDist + 1.5, path.getLength());
            targetLoc = path.getPointAtDistance(projectionDist);
        }
        return new SteeringResult(targetLoc, type);
    }

    /**
     * Projects the agent's position onto the path spline to find the closest point `t`.
     * Uses a sliding window search around `currentDist` for efficiency.
     */
    private void updateProjection(Location agentPos) {
        double bestDist = currentDist;
        double minDeviation = Double.MAX_VALUE;
        // Search range: [current - 1.0, current + SEARCH_WINDOW]
        // We step in 0.5m increments for coarse search, then refine?
        // Actually, since we have segments, we can iterate segments.
        // For simplicity and performance, we scan points at fixed resolution.
        double startSearch = Math.max(0, currentDist - 1.0);
        double endSearch = Math.min(path.getLength(), currentDist + SEARCH_WINDOW);
        double step = 0.5;
        for (double d = startSearch; d <= endSearch; d += step) {
            Location point = path.getPointAtDistance(d);
            double deviation = point.distanceSquared(agentPos);
            if (deviation < minDeviation) {
                minDeviation = deviation;
                bestDist = d;
            }
        }
        // Teleport/Knockback detection
        // If the best match is at the very edge of our search window AND deviation is high (> 4 blocks),
        // we likely lost the track due to external movement. Perform a full scan.
        boolean atEdge = (bestDist >= endSearch - step) || (bestDist <= startSearch + step);
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
        double bestDist = 0.0;
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
}