package com.ionsignal.minecraft.iongenesis.model.geometry;

import java.util.HashSet;
import java.util.Set;

/**
 * Simple implementation of CollisionDetector using a HashSet.
 * Sufficient for Phase 4, can be optimized later.
 */
public class NaiveCollisionDetector implements CollisionDetector {
    private final Set<AABB> occupiedSpace = new HashSet<>();

    @Override
    public void add(AABB aabb) {
        occupiedSpace.add(aabb);
    }

    @Override
    public boolean collides(AABB query) {
        for (AABB occupied : occupiedSpace) {
            if (query.intersects(occupied)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int size() {
        return occupiedSpace.size();
    }
}
