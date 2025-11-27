package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Represents a sequence of locations for an entity to follow, enriched with metadata.
 * Acts as a spline for continuous interpolation.
 */
public class Path {
    @SuppressWarnings("unused")
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    private final List<PathNode> nodes;
    private final World world;

    // Cached data for spline interpolation
    private final double[] cumulativeDistances;
    private final double totalLength;

    // Legacy support
    private final List<Location> legacyPoints;

    /**
     * Constructs a new Path from enriched PathNodes.
     *
     * @param nodes
     *            The list of PathNodes.
     * @param world
     *            The world in which the path exists.
     */
    public Path(List<PathNode> nodes, World world) {
        this.nodes = Collections.unmodifiableList(nodes);
        this.world = world;

        // Pre-calculate spline distances
        this.cumulativeDistances = new double[nodes.size()];
        this.legacyPoints = new ArrayList<>(nodes.size());

        double lengthAccumulator = 0.0;
        if (!nodes.isEmpty()) {
            cumulativeDistances[0] = 0.0;
            legacyPoints.add(nodes.get(0).toLocation(world));

            for (int i = 1; i < nodes.size(); i++) {
                Location prev = nodes.get(i - 1).toLocation(world);
                Location curr = nodes.get(i).toLocation(world);

                lengthAccumulator += prev.distance(curr);
                cumulativeDistances[i] = lengthAccumulator;
                legacyPoints.add(curr);
            }
        }
        this.totalLength = lengthAccumulator;
    }

    /**
     * Retrieves the destination location of the segment occurring at the given distance.
     * For a segment A -> B, if distance falls between A and B, this returns B.
     */
    public Location getSegmentDestination(double distance) {
        if (nodes.isEmpty()) {
            return null;
        }
        int index = findSegmentIndex(distance);
        // The destination is the NEXT node.
        int nextIndex = Math.min(index + 1, nodes.size() - 1);
        return nodes.get(nextIndex).toLocation(world);
    }

    /**
     * Interpolates a point along the path spline at the given distance from the start.
     * 
     * @param distance
     *            The distance in meters from the start of the path.
     * @return The interpolated Location.
     */
    public Location getPointAtDistance(double distance) {
        if (nodes.isEmpty())
            return null;
        if (distance <= 0)
            return nodes.get(0).toLocation(world);
        if (distance >= totalLength)
            return nodes.get(nodes.size() - 1).toLocation(world);
        // Binary search for the segment
        int index = findSegmentIndex(distance);
        // Linear Interpolation
        double distA = cumulativeDistances[index];
        double distB = cumulativeDistances[index + 1];
        double segmentLength = distB - distA;
        if (segmentLength < 0.001)
            return nodes.get(index).toLocation(world);
        double t = (distance - distA) / segmentLength;
        Location locA = nodes.get(index).toLocation(world);
        Location locB = nodes.get(index + 1).toLocation(world);
        // Lerp
        Vector vecA = locA.toVector();
        Vector vecB = locB.toVector();
        Vector result = vecA.add(vecB.subtract(vecA).multiply(t));
        return result.toLocation(world);
    }

    /**
     * Retrieves the metadata for the node preceding the given distance.
     */
    public PathNode getNodeAtDistance(double distance) {
        if (nodes.isEmpty())
            return null;
        if (distance <= 0)
            return nodes.get(0);
        if (distance >= totalLength)
            return nodes.get(nodes.size() - 1);

        int index = findSegmentIndex(distance);
        return nodes.get(index);
    }

    /**
     * Retrieves the cumulative distance to the END of the segment containing the given distance.
     * For a segment A -> B where distance falls between A and B, this returns the distance to B.
     *
     * @param distance
     *            The current distance along the path.
     * @return The cumulative distance to the next node, or totalLength if at/past the end.
     */
    public double getNextNodeDistance(double distance) {
        if (nodes.isEmpty() || nodes.size() < 2) {
            return totalLength;
        }
        if (distance >= totalLength) {
            return totalLength;
        }
        int index = findSegmentIndex(distance);
        int nextIndex = index + 1;
        // Clamp to array bounds (handles edge case at final segment)
        return nextIndex < cumulativeDistances.length ? cumulativeDistances[nextIndex] : totalLength;
    }

    private int findSegmentIndex(double distance) {
        // Binary search to find i such that cumulativeDistances[i] <= distance < cumulativeDistances[i+1]
        int low = 0;
        int high = cumulativeDistances.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            double midVal = cumulativeDistances[mid];

            if (midVal < distance) {
                low = mid + 1;
            } else if (midVal > distance) {
                high = mid - 1;
            } else {
                return mid; // Exact match
            }
        }
        // low is the insertion point. We want the index below it.
        return Math.max(0, Math.min(low - 1, cumulativeDistances.length - 2));
    }

    public double getLength() {
        return totalLength;
    }

    // --- Legacy / Compatibility Methods ---

    public List<Location> waypoints() {
        return legacyPoints;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int size() {
        return nodes.size();
    }

    public Location getPoint(int index) {
        return legacyPoints.get(index);
    }

    public Vector getDirectionAtIndex(int index) {
        if (index < 0 || index >= legacyPoints.size() - 1) {
            return new Vector(0, 0, 0);
        }
        Location current = legacyPoints.get(index);
        Location next = legacyPoints.get(index + 1);
        return next.toVector().subtract(current.toVector()).normalize();
    }
}