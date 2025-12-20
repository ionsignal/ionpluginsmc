package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import net.minecraft.core.BlockPos;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a sequence of locations for an entity to follow, enriched with metadata.
 * Acts as a spline for continuous interpolation.
 */
public class Path {
    private final World world;
    private final Location startLocation;
    private final List<PathNode> nodes;

    // Cached data for spline interpolation
    private final double verticalOffset;
    private final double totalLength;
    private final double[] cumulativeDistances;

    // Legacy support for DebugVisualizer
    private final List<Location> points;

    // Physical Lookup Maps
    private final Map<BlockPos, PathNode> nodeMap;
    private final Map<BlockPos, Integer> nodeIndexMap;

    /**
     * Constructs a new Path from enriched PathNodes with a "Safe Splice" from the exact start.
     */
    public Path(Location actualStart, List<PathNode> nodes, World world, double verticalOffset) {
        this.world = world;
        this.startLocation = actualStart;
        this.verticalOffset = verticalOffset;
        this.nodes = Collections.unmodifiableList(nodes);
        // Initialize Lookup Maps
        this.nodeMap = new HashMap<>(nodes.size());
        this.nodeIndexMap = new HashMap<>(nodes.size());
        // Pre-calculate spline distances and populate maps
        this.cumulativeDistances = new double[nodes.size()];
        this.points = new ArrayList<Location>(nodes.size());
        double lengthAccumulator = 0.0;
        if (!nodes.isEmpty()) {
            // Segment 0: actualStart -> nodes[0]
            Location firstNodeLoc = nodes.get(0).toLocation(world);
            double spliceDist = actualStart.distance(firstNodeLoc);
            cumulativeDistances[0] = spliceDist;
            lengthAccumulator = spliceDist;
            points.add(firstNodeLoc);
            // Index first node
            nodeMap.put(nodes.get(0).pos(), nodes.get(0));
            nodeIndexMap.put(nodes.get(0).pos(), 0);
            for (int i = 1; i < nodes.size(); i++) {
                Location prev = nodes.get(i - 1).toLocation(world);
                Location curr = nodes.get(i).toLocation(world);
                lengthAccumulator += prev.distance(curr);
                cumulativeDistances[i] = lengthAccumulator;
                points.add(curr);
                // Index node
                nodeMap.put(nodes.get(i).pos(), nodes.get(i));
                nodeIndexMap.put(nodes.get(i).pos(), i);
            }
        }
        this.totalLength = lengthAccumulator;
    }

    /**
     * Retrieves the PathNode at the specific physical block position. O(1) lookup.
     */
    public PathNode getNodeAt(BlockPos pos) {
        return nodeMap.get(pos);
    }

    /**
     * Retrieves the node immediately following the provided node in the path sequence.
     * Used to determine the destination of a maneuver starting at 'current'.
     * 
     * @return The next node, or the current node if it is the end of the path.
     */
    public PathNode getNextNode(PathNode current) {
        Integer index = nodeIndexMap.get(current.pos());
        if (index == null)
            return current; // Should not happen if node belongs to path
        int nextIndex = index + 1;
        if (nextIndex >= nodes.size()) {
            return current;
        }
        return nodes.get(nextIndex);
    }

    /**
     * Interpolates a point along the path spline at the given distance from the start.
     */
    public Location getPointAtDistance(double distance) {
        if (nodes.isEmpty())
            return startLocation;
        if (distance <= 0)
            return startLocation;
        if (distance >= totalLength)
            return nodes.get(nodes.size() - 1).toLocation(world);
        // Splice segment
        if (distance < cumulativeDistances[0]) {
            double len = cumulativeDistances[0];
            if (len < 0.001)
                return nodes.get(0).toLocation(world);
            double t = distance / len;
            Vector vecA = startLocation.toVector();
            Vector vecB = nodes.get(0).toLocation(world).toVector();
            return vecA.add(vecB.subtract(vecA).multiply(t)).toLocation(world);
        }
        if (nodes.size() < 2)
            return nodes.get(0).toLocation(world);
        // Standard Segment
        int index = findSegmentIndex(distance);
        double distA = cumulativeDistances[index];
        double distB = cumulativeDistances[index + 1];
        double segmentLength = distB - distA;
        if (segmentLength < 0.001)
            return nodes.get(index).toLocation(world);
        double t = (distance - distA) / segmentLength;
        Location locA = nodes.get(index).toLocation(world);
        Location locB = nodes.get(index + 1).toLocation(world);
        Vector vecA = locA.toVector();
        Vector vecB = locB.toVector();
        return vecA.add(vecB.subtract(vecA).multiply(t)).toLocation(world);
    }

    /**
     * Retrieves the metadata for the node preceding the given distance.
     * Retained for Spline Lookahead logic in PathFollower.
     */
    public PathNode getNodeAtDistance(double distance) {
        if (nodes.isEmpty())
            return null;
        if (distance < cumulativeDistances[0]) {
            return nodes.get(0);
        }
        if (distance >= totalLength)
            return nodes.get(nodes.size() - 1);
        int index = findSegmentIndex(distance);
        return nodes.get(index);
    }

    /**
     * Retrieves the cumulative distance to the END of the segment containing the given distance.
     */
    public double getNextNodeDistance(double distance) {
        if (nodes.isEmpty())
            return totalLength;
        if (distance < cumulativeDistances[0])
            return cumulativeDistances[0];
        if (nodes.size() < 2 || distance >= totalLength)
            return totalLength;
        int index = findSegmentIndex(distance);
        int nextIndex = index + 1;
        return nextIndex < cumulativeDistances.length ? cumulativeDistances[nextIndex] : totalLength;
    }

    private int findSegmentIndex(double distance) {
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
                return mid;
            }
        }
        return Math.max(0, Math.min(low - 1, cumulativeDistances.length - 2));
    }

    public double getLength() {
        return totalLength;
    }

    public World getWorld() {
        return world;
    }

    public double getVerticalOffset() {
        return verticalOffset;
    }

    public List<Location> waypoints() {
        return points;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }
}