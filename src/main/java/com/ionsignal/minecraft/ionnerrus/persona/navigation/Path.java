package com.ionsignal.minecraft.ionnerrus.persona.navigation;

// import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import net.minecraft.core.BlockPos;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Represents a sequence of locations for an entity to follow.
 * This class is immutable after creation.
 */
public class Path {
    private static final int SPLINE_POINTS_PER_SEGMENT = 10;
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    private final List<Location> points;
    private final List<Location> waypoints;
    private final List<Location> smoothedPoints;

    private double cachedLength = -1.0;

    /**
     * Constructs a new Path from a list of BlockPos objects.
     * The BlockPos coordinates are converted to centered Locations and a simplified
     * list of waypoints is generated.
     *
     * @param positions The list of BlockPos objects representing the path.
     * @param world The world in which the path exists.
     */
    public Path(List<BlockPos> positions, World world) {
        List<Location> initialPoints = positions.stream()
                .map(pos -> new Location(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))
                .collect(Collectors.toUnmodifiableList());
        this.points = initialPoints;
        this.waypoints = simplifyPath(initialPoints);
        this.smoothedPoints = PathSmoother.generateSpline(this.waypoints, SPLINE_POINTS_PER_SEGMENT);
        // DEBUG
        this.debug(this.waypoints);
    }

    /**
     * Constructs a new Path from a pre-existing list of Locations.
     * A simplified list of waypoints is generated from the provided points.
     *
     * @param points The list of locations.
     */
    public Path(List<Location> points) {
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.waypoints = simplifyPath(this.points);
        this.smoothedPoints = PathSmoother.generateSpline(this.waypoints, SPLINE_POINTS_PER_SEGMENT);
        // DEBUG
        this.debug(this.waypoints);
    }

    private void debug(List<Location> waypoints) {
        StringBuilder sb = new StringBuilder("Path created with " + waypoints.size() + " waypoints:");
        for (int i = 0; i < waypoints.size(); i++) {
            Location loc = waypoints.get(i);
            sb.append(String.format("\n  [%d] -> (%.1f, %.1f, %.1f)", i, loc.getX(), loc.getY(), loc.getZ()));
        }
        LOGGER.info(sb.toString());
    }

    /**
     * Simplifies a detailed path into a list of key waypoints (corners).
     *
     * @param pathPoints The detailed list of locations.
     * @return An unmodifiable list of waypoint locations.
     */
    private List<Location> simplifyPath(List<Location> pathPoints) {
        if (pathPoints.size() < 3) {
            return pathPoints;
        }

        List<Location> simplifiedWaypoints = new ArrayList<>();
        simplifiedWaypoints.add(pathPoints.get(0));

        Vector lastDirection = pathPoints.get(1).toVector().subtract(pathPoints.get(0).toVector()).normalize();

        for (int i = 2; i < pathPoints.size(); i++) {
            Location p1 = pathPoints.get(i - 1);
            Location p2 = pathPoints.get(i);
            Vector currentDirection = p2.toVector().subtract(p1.toVector());

            if (currentDirection.lengthSquared() == 0)
                continue;
            currentDirection.normalize();

            // A small tolerance might be needed for non-grid-aligned movements,
            // but for A* on a grid, direct comparison is usually fine.
            if (currentDirection.distanceSquared(lastDirection) > 0.001) {
                simplifiedWaypoints.add(p1); // The point before the direction change is the corner
                lastDirection = currentDirection;
            }
        }

        simplifiedWaypoints.add(pathPoints.get(pathPoints.size() - 1)); // Always add the last point
        return Collections.unmodifiableList(simplifiedWaypoints);
    }

    /**
     * Gets the simplified list of waypoints for navigation.
     *
     * @return An unmodifiable list of waypoint locations.
     */
    public List<Location> waypoints() {
        return waypoints;
    }

    /**
     * Gets the full, detailed list of locations that make up this path.
     *
     * @return An unmodifiable list of locations.
     */
    public List<Location> getPoints() {
        return points;
    }

    /**
    * Gets the smoothed points of the path.
    *
    * @return An unmodifiable list of smooth points.
    */
    public List<Location> getSmoothedPoints() {
        return smoothedPoints;
    }

    /**
     * Checks if the path contains any points.
     *
     * @return true if the path is empty, false otherwise.
     */
    public boolean isEmpty() {
        return points.isEmpty();
    }

    /**
     * Gets the number of points in the detailed path.
     *
     * @return The size of the path.
     */
    public int size() {
        return points.size();
    }

    /**
     * Calculates the total geometric length of the path by summing the distances
     * between consecutive waypoints. The result is cached after the first calculation.
     *
     * @return The total length of the path.
     */
    public double getLength() {
        if (cachedLength >= 0) {
            return cachedLength;
        }
        if (waypoints.size() < 2) {
            this.cachedLength = 0.0;
            return 0.0;
        }

        double totalLength = 0.0;
        // Use waypoints for length calculation for efficiency, as they represent the key turns.
        for (int i = 1; i < waypoints.size(); i++) {
            totalLength += waypoints.get(i - 1).distance(waypoints.get(i));
        }

        this.cachedLength = totalLength;
        return this.cachedLength;
    }
}