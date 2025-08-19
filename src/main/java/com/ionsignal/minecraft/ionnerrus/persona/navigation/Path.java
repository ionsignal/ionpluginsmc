package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import net.minecraft.core.BlockPos;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

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
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();
    private final List<Location> points;
    private final List<Location> waypoints;
    private double cachedLength = -1.0;

    /**
     * Constructs a new Path from a list of BlockPos objects.
     * The BlockPos coordinates are converted to centered Locations and a simplified
     * list of waypoints is generated.
     *
     * @param positions
     *            The list of BlockPos objects representing the path.
     * @param world
     *            The world in which the path exists.
     */
    public Path(List<BlockPos> positions, World world) {
        List<Location> initialPoints = positions.stream()
                .map(pos -> new Location(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))
                .collect(Collectors.toUnmodifiableList());
        this.points = initialPoints;
        this.waypoints = initialPoints;
        // this.debug(this.waypoints);
    }

    /**
     * Constructs a new Path from a pre-existing list of Locations.
     * A simplified list of waypoints is generated from the provided points.
     *
     * @param points
     *            The list of locations.
     */
    public Path(List<Location> points) {
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.waypoints = this.points;
        // this.debug(this.waypoints);
    }

    @SuppressWarnings("unused")
    private void debug(List<Location> waypoints) {
        StringBuilder sb = new StringBuilder("Path created with " + waypoints.size() + " waypoints:");
        for (int i = 0; i < waypoints.size(); i++) {
            Location loc = waypoints.get(i);
            sb.append(String.format("\n [%d] -> (%.1f, %.1f, %.1f)", i, loc.getX(), loc.getY(), loc.getZ()));
        }
        LOGGER.info(sb.toString());
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
    // CHANGE: Renamed to getPoints and made package-private for controlled access.
    List<Location> getPoints() {
        return points;
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
     * Gets a specific point from the detailed path by its index.
     * 
     * @param index
     *            The index of the point.
     * @return The Location at the specified index.
     * @throws IndexOutOfBoundsException
     *             if the index is out of range.
     */
    public Location getPoint(int index) {
        if (index < 0 || index >= points.size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + points.size());
        }
        return points.get(index);
    }

    /**
     * Calculates the normalized direction vector from one point to the next in the path.
     * 
     * @param index
     *            The starting index of the path segment.
     * @return A normalized Vector representing the direction of travel. Returns a zero vector if at the end of the path.
     */
    public Vector getDirectionAtIndex(int index) {
        if (index < 0 || index >= points.size() - 1) {
            return new Vector(0, 0, 0);
        }
        Location current = points.get(index);
        Location next = points.get(index + 1);
        return next.toVector().subtract(current.toVector()).normalize();
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
        // Use waypoints for length calculation for efficiency,
        // as they represent the key turns.
        double totalLength = 0.0;
        for (int i = 1; i < waypoints.size(); i++) {
            totalLength += waypoints.get(i - 1).distance(waypoints.get(i));
        }
        this.cachedLength = totalLength;
        return this.cachedLength;
    }
}