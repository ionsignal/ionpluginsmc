package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

public class PathFollower {
    private static final double JUMP_DETECTION_THRESHOLD = 0.8;
    private static final int JUMP_SCAN_AHEAD = 3;
    private static final int LOOKAHEAD_POINTS = 15;
    private static final double WAYPOINT_REACHED_DISTANCE_SQUARED = 1.0;

    private final Path path;
    private int currentIndex;

    public PathFollower(Path path) {
        this.path = path;
        this.currentIndex = 0;
    }

    public SteeringResult calculateSteering(Location currentPos) {
        updateCurrentIndex(currentPos);
        if (isFinished(currentPos)) {
            // If finished, just steer towards the final point to ensure arrival.
            return new SteeringResult(path.getPoint(path.size() - 1), SteeringResult.MovementType.WALK);
        }
        // Scan ahead for immediate jumps
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
        for (int i = currentIndex + 1; i < Math.min(currentIndex + LOOKAHEAD_POINTS, path.size()); i++) {
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

    private void updateCurrentIndex(Location currentPos) {
        // Find the closest point on the path from the current index onwards.
        // This prevents the follower from backtracking on the path.
        // Search a limited window ahead of the current index to find the closest point
        double closestDistSq = Double.MAX_VALUE;
        int bestIndex = this.currentIndex;
        for (int i = this.currentIndex; i < Math.min(this.currentIndex + 10, path.size()); i++) {
            double distSq = path.getPoint(i).distanceSquared(currentPos);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                bestIndex = i;
            }
        }
        this.currentIndex = bestIndex;
    }

    private boolean hasLineOfSight(Location from, Location to, World world) {
        // Simple block-based raycast
        Location eyeLocation = from.clone().add(0, 1.6, 0); // Approx eye height
        Vector direction = to.toVector().subtract(eyeLocation.toVector());
        double distance = direction.length();
        if (distance < 1.0) {
            return true; // Too close to have an obstacle
        }
        direction.normalize();
        for (double d = 1.0; d < distance; d += 0.5) {
            Location checkLoc = eyeLocation.clone().add(direction.clone().multiply(d));
            if (checkLoc.getBlock().getType().isOccluding()) {
                return false;
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