package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

public class PathFollower {
    private static final double JUMP_DETECTION_THRESHOLD = 0.8;
    private static final double DROP_DETECTION_THRESHOLD = -0.8;
    private static final int JUMP_LOOKAHEAD_NODES = 1;
    private static final int SMOOTHING_LOOKAHEAD = 6;
    private static final double WAYPOINT_REACHED_DISTANCE_SQUARED = 0.95 * 0.95;
    private static final double PERSONA_SHOULDER_WIDTH = 0.2;
    private static final double TURN_DETECTION_THRESHOLD = 0.1;

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
        // Check for drops before checking for jumps to ensure we correctly walk off ledges.
        if (currentIndex < path.size() - 1) {
            Location currentPathPoint = path.getPoint(currentIndex);
            Location nextPoint = path.getPoint(currentIndex + 1);
            double heightDifference = nextPoint.getY() - currentPathPoint.getY();
            // Check for a significant drop between the current path node and the next one.
            if (heightDifference < DROP_DETECTION_THRESHOLD) {
                // We need to perform a controlled drop. The target is the landing spot.
                return new SteeringResult(nextPoint, SteeringResult.MovementType.DROP);
            }
        }
        // Scan ahead for immediate jumps.
        for (int i = currentIndex; i < Math.min(currentIndex + JUMP_LOOKAHEAD_NODES, path.size() - 1); i++) {
            // Check persona's actual position vs target and only trigger jump if we need to go UP from current
            // position, add an upper bound check to prevent jumping for unreachable heights
            Location nextPoint = path.getPoint(i + 1);
            double heightDifferenceFromPersona = nextPoint.getY() - currentPos.getY();
            if (heightDifferenceFromPersona > JUMP_DETECTION_THRESHOLD && heightDifferenceFromPersona < 2.0) {
                // Additional validation - only jump if the next point is actually higher than us and we're
                // relatively close horizontally (within 3 blocks)
                double horizontalDistance = Math.sqrt(Math.pow(nextPoint.getX() - currentPos.getX(), 2) +
                        Math.pow(nextPoint.getZ() - currentPos.getZ(), 2));
                if (horizontalDistance < 3.0) {
                    return new SteeringResult(nextPoint, SteeringResult.MovementType.JUMP);
                }
            }
        }
        // Scan ahead for the furthest visible steering target
        Location steeringTarget = path.getPoint(Math.min(currentIndex + 1, path.size() - 1));
        World world = currentPos.getWorld();
        if (world == null) {
            // If world is somehow null, can't do line-of-sight; return the very next point.
            return new SteeringResult(steeringTarget, SteeringResult.MovementType.WALK);
        }
        // Width-aware path smoothing loop requires at least two points ahead to define a turn.
        if (currentIndex + 1 < path.size()) {
            Location pivotPoint = path.getPoint(currentIndex + 1);
            for (int i = currentIndex + 2; i < Math.min(currentIndex + SMOOTHING_LOOKAHEAD, path.size()); i++) {
                Location candidatePoint = path.getPoint(i);
                Vector offset = new Vector(0, 0, 0); // Default to no offset
                if (Math.abs(candidatePoint.getY() - pivotPoint.getY()) > 0.1) {
                    break;
                }
                // Define the vectors that form the turn we are trying to shortcut.
                // v1 is from our current position to the pivot corner.
                // v2 is from the pivot corner to the candidate destination.
                Vector v1 = pivotPoint.toVector().subtract(currentPos.toVector());
                Vector v2 = candidatePoint.toVector().subtract(pivotPoint.toVector());
                if (v1.lengthSquared() < 0.001) {
                    // Fall back to a simple, non-offset line-of-sight check for this candidate
                    if (NavigationHelper.hasLineOfSight(currentPos, candidatePoint, world)) {
                        steeringTarget = candidatePoint;
                    } else {
                        break;
                    }
                    continue; // Move to the next candidate point in the loop
                }
                // Use a 2D cross-product on the XZ plane to determine the turn direction.
                // This is a highly efficient way to check geometry without expensive trig.
                double turnValue = (v1.getX() * v2.getZ()) - (v1.getZ() * v2.getX());
                if (Math.abs(turnValue) > TURN_DETECTION_THRESHOLD) {
                    // Calculate the "right" vector perpendicular to our direction of travel (v1).
                    Vector rightVec = new Vector(v1.getZ(), 0, -v1.getX()).normalize();
                    if (turnValue > 0) { // Left turn
                        // The inside shoulder is the left one. The offset is the *negative* right vector.
                        offset = rightVec.multiply(-PERSONA_SHOULDER_WIDTH);
                    } else { // Right turn
                        // The inside shoulder is the right one. The offset is the positive right vector.
                        offset = rightVec.multiply(PERSONA_SHOULDER_WIDTH);
                    }
                }
                // The starting point for our raycast is offset to the inside shoulder.
                Location raycastStart = currentPos.clone().add(offset);
                if (NavigationHelper.hasLineOfSight(raycastStart, candidatePoint, world)) {
                    steeringTarget = candidatePoint;
                } else {
                    // As soon as we lose line of sight from our shoulder, we stop.
                    // The last visible point is our target.
                    break;
                }
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

    public int getCurrentIndex() {
        return currentIndex;
    }
}