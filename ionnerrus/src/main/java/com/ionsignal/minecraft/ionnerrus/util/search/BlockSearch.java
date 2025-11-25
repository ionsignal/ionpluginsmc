package com.ionsignal.minecraft.ionnerrus.util.search;

import com.ionsignal.minecraft.ionnerrus.persona.navigation.WorldSnapshot;

import net.minecraft.core.BlockPos;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Provides a generic, thread-safe utility for performing a Dijkstra-like "spill-fill" search on a
 * WorldSnapshot.
 */
public final class BlockSearch {
    private BlockSearch() {
    }

    /**
     * An immutable data carrier for a node in the search space.
     * Implements Comparable to enable sorting by distance in a PriorityQueue.
     */
    public record TraversalNode(BlockPos pos, double distance) implements Comparable<TraversalNode> {
        @Override
        public int compareTo(TraversalNode other) {
            return Double.compare(this.distance, other.distance);
        }
    }

    /**
     * A functional interface defining the rules of movement for the search.
     * Implementations of this interface must be thread-safe and operate only on the provided snapshot.
     */
    @FunctionalInterface
    public interface INeighborStrategy {
        List<TraversalNode> getNeighbors(TraversalNode currentNode, World world, WorldSnapshot snapshot, Set<BlockPos> visitedNodes);
    }

    /**
     * A generic functional interface defining the logic for what to look for at each node.
     * Implementations of this interface must be thread-safe and operate only on the provided snapshot.
     */
    @FunctionalInterface
    public interface ISearchProcessor<T> {
        List<T> process(TraversalNode node, World world, WorldSnapshot snapshot);
    }

    /**
     * The core static method for finding reachable items of type T.
     *
     * @param start
     *            The starting location for the search.
     * @param maxDistance
     *            The maximum distance from the start to search.
     * @param maxResults
     *            The search will terminate early once this many results are found.
     * @param neighborStrategy
     *            The strategy defining how the search expands (movement rules).
     * @param searchProcessor
     *            The processor that checks each node for desired results.
     * @param snapshot
     *            The thread-safe snapshot of the world to search within.
     * @param <T>
     *            The type of the result to be returned.
     * @return A list of found results.
     */
    public static <T> List<T> findReachable(
            Location start,
            double maxDistance,
            int maxResults,
            INeighborStrategy neighborStrategy,
            ISearchProcessor<T> searchProcessor,
            WorldSnapshot snapshot) {

        List<T> results = new ArrayList<>();
        World world = start.getWorld();
        if (world == null)
            return results;

        Optional<Location> groundOpt = snapshot.findGroundBelow(start, 10);
        if (groundOpt.isEmpty())
            return results;
        BlockPos startPos = new BlockPos(groundOpt.get().getBlockX(), groundOpt.get().getBlockY(), groundOpt.get().getBlockZ());

        PriorityQueue<TraversalNode> frontier = new PriorityQueue<>();
        Set<BlockPos> visited = new HashSet<>();

        frontier.add(new TraversalNode(startPos, 0.0));
        visited.add(startPos);

        while (!frontier.isEmpty()) {
            TraversalNode currentNode = frontier.poll();

            if (currentNode.distance() > maxDistance) {
                break; // We've reached the search limit
            }

            results.addAll(searchProcessor.process(currentNode, world, snapshot));

            if (results.size() >= maxResults) {
                break; // We've found enough results
            }

            List<TraversalNode> neighbors = neighborStrategy.getNeighbors(currentNode, world, snapshot, visited);
            for (TraversalNode neighbor : neighbors) {
                if (!visited.contains(neighbor.pos())) {
                    visited.add(neighbor.pos());
                    frontier.add(neighbor);
                }
            }
        }

        return results;
    }
}