package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

public class PathFollower {
    private static final double JUMP_DETECTION_THRESHOLD = 0.8;
    private static final int JUMP_SCAN_AHEAD = 3;
    private static final int SMOOTHING_LOOKAHEAD = 5;
    private static final double WAYPOINT_REACHED_DISTANCE_SQUARED = 0.5 * 0.5; // 1 block radius

    private final Path path;
    private int currentIndex;

    public PathFollower(Path path) {
        this.path = path;
        this.currentIndex = 0;
    }

    public SteeringResult calculateSteering(Location currentPos) {
        if (isFinished(currentPos)) {
            // If finished, just steer towards the final point to ensure arrival.
            return new SteeringResult(path.getPoint(path.size() - 1), SteeringResult.MovementType.WALK);
        }
        // Index is now updated sequentially at the start of the calculation.
        updateCurrentIndex(currentPos);
        // Scan ahead for immediate jumps. This is a high-priority check.
        for (int i = currentIndex; i < Math.min(currentIndex + JUMP_SCAN_AHEAD, path.size() - 1); i++) {
            Location currentPoint = path.getPoint(i);
            Location nextPoint = path.getPoint(i + 1);
            if (nextPoint.getY() - currentPoint.getY() > JUMP_DETECTION_THRESHOLD) {
                return new SteeringResult(nextPoint, SteeringResult.MovementType.JUMP);
            }
        }
        // Scan ahead for the furthest visible steering target
        Location steeringTarget = path.getPoint(Math.min(currentIndex + 1, path.size() - 1));
        World world = currentPos.getWorld();
        if (world == null) {
            // If world is somehow null, can't do line-of-sight; return the very next point.
            return new SteeringResult(steeringTarget, SteeringResult.MovementType.WALK);
        }
        for (int i = currentIndex + 2; i < Math.min(currentIndex + SMOOTHING_LOOKAHEAD, path.size()); i++) {
            Location candidatePoint = path.getPoint(i);
            if (hasLineOfSight(currentPos, candidatePoint, world)) {
                steeringTarget = candidatePoint;
            } else {
                // As soon as we lose line of sight, we stop. The last visible point is our target.
                break;
            }
        }
        return new SteeringResult(steeringTarget, SteeringResult.MovementType.WALK);
    }

    /**
     * Advances the current path index using a lenient, progression-based check. The index is moved
     * forward if the agent is either very close to the next waypoint or has clearly moved past it along
     * the path's direction. This method uses a loop to allow the agent to "catch up" if it moves fast
     * enough to pass multiple waypoints in a single tick.
     */
    private void updateCurrentIndex(Location currentPos) {
        // Loop to handle skipping multiple waypoints in a single tick
        while (currentIndex < path.size() - 1) {
            Location currentWaypoint = path.getPoint(currentIndex);
            Location nextWaypoint = path.getPoint(currentIndex + 1);
            // Proximity Check (strict check)
            // Useful for when the agent stops precisely on a waypoint.
            boolean isCloseEnough = currentPos.distanceSquared(nextWaypoint) < WAYPOINT_REACHED_DISTANCE_SQUARED;
            // Progression Check (lenient check)
            // Determines if the agent has "passed" the waypoint's perpendicular plane.
            Vector pathSegmentDirection = nextWaypoint.toVector().subtract(currentWaypoint.toVector());
            Vector agentToNextWaypoint = nextWaypoint.toVector().subtract(currentPos.toVector());
            boolean hasPassedWaypoint = agentToNextWaypoint.dot(pathSegmentDirection) < 0;
            if (isCloseEnough || hasPassedWaypoint) {
                currentIndex++; // We've met a condition to advance, check the next waypoint.
            } else {
                break;
            }
        }
    }

    /**
     * Checks for a clear line of sight between two points. This check is more robust, using
     * NavigationHelper.isPassable to correctly identify obstacles like leaves and fences that are not
     * technically "occluding".
     */
    private boolean hasLineOfSight(Location from, Location to, World world) {
        Location eyeLocation = from.clone().add(0, 1.6, 0); // Approx eye height
        Vector direction = to.toVector().subtract(eyeLocation.toVector());
        double distance = direction.length();
        if (distance < 1.0) {
            return true; // Too close to have an obstacle
        }
        direction.normalize();
        // Raycast step; smaller step for more accuracy in dense environments
        for (double d = 0.5; d < distance; d += 0.5) {
            Location checkLoc = eyeLocation.clone().add(direction.clone().multiply(d));
            // Check if a block is not passable, and it blocks line of sight.
            if (!NavigationHelper.isPassable(checkLoc.getBlock())) {
                return false;
            }
            // Check for a drop-off. If the block below our path is passable, it's a cliff
            Location groundCheckLoc = checkLoc.clone().subtract(0, 2.2, 0);
            if (NavigationHelper.isPassable(groundCheckLoc.getBlock())) {
                return false; // Path goes over a ledge, break line of sight.
            }
        }
        return true;
    }

    public boolean isFinished(Location currentPos) {
        if (path.isEmpty()) {
            return true;
        }
        // Check if we are at the last point on the path
        if (currentIndex >= path.size() - 1) {
            Location endPoint = path.getPoint(path.size() - 1);
            // And if we are physically close enough to it.
            return currentPos.distanceSquared(endPoint) < WAYPOINT_REACHED_DISTANCE_SQUARED;
        }
        return false;
    }
}