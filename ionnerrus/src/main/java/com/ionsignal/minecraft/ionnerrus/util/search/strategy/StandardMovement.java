package com.ionsignal.minecraft.ionnerrus.util.search.strategy;

import com.ionsignal.minecraft.ionnerrus.util.search.BlockSearch;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.WorldSnapshot;

import net.minecraft.core.BlockPos;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A concrete, thread-safe implementation of INeighborStrategy that defines standard ground-based
 * movement (walking, stepping up/down, dropping). It operates exclusively on a WorldSnapshot.
 */
public class StandardMovement implements BlockSearch.INeighborStrategy {
    private static final int MAX_DROP_DISTANCE = 5;

    @Override
    public List<BlockSearch.TraversalNode> getNeighbors(BlockSearch.TraversalNode currentNode, World world, WorldSnapshot snapshot,
            Set<BlockPos> visitedNodes) {
        List<BlockSearch.TraversalNode> neighbors = new ArrayList<>();
        BlockPos currentPos = currentNode.pos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;
                BlockPos adjacentPos = currentPos.offset(dx, 0, dz);
                boolean foundWalkable = false;
                // 1. Check for walk/step up/step down
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos targetPos = adjacentPos.atY(currentPos.getY() + dy);
                    // Use NavigationHelper for strict consistency with Pathfinder
                    if (NavigationHelper.isValidStandingSpot(snapshot, targetPos)) {
                        addNeighbor(neighbors, currentNode, targetPos);
                        foundWalkable = true;
                        break; // Found a valid spot, no need to check for drops in this direction
                    }
                }
                // 2. If no walkable path, check for a drop
                // Use NavigationHelper.isClear to allow dropping through Air/Grass
                if (!foundWalkable && NavigationHelper.isClear(snapshot, adjacentPos)
                        && NavigationHelper.isClear(snapshot, adjacentPos.above())) {
                    BlockPos landingSpot = findLandingSpot(adjacentPos, snapshot);
                    if (landingSpot != null) {
                        addNeighbor(neighbors, currentNode, landingSpot);
                    }
                }
            }
        }
        return neighbors;
    }

    @SuppressWarnings("null")
    private void addNeighbor(List<BlockSearch.TraversalNode> neighbors, BlockSearch.TraversalNode parent, BlockPos neighborPos) {
        double distance = parent.pos().distManhattan(neighborPos);
        neighbors.add(new BlockSearch.TraversalNode(neighborPos, parent.distance() + distance));
    }

    private BlockPos findLandingSpot(BlockPos start, WorldSnapshot snapshot) {
        BlockPos current = start;
        for (int i = 0; i < MAX_DROP_DISTANCE; i++) {
            current = current.below();
            // Use NavigationHelper
            if (NavigationHelper.isValidStandingSpot(snapshot, current)) {
                return current;
            }
        }
        return null;
    }
}