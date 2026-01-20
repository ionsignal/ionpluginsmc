package com.ionsignal.minecraft.iongenesis.model.geometry;

/**
 * Abstraction for collision detection logic.
 * Allows decoupling the storage mechanism (Set vs Spatial Hash) from the logic.
 */
public interface CollisionDetector {
    void add(AABB aabb);

    boolean collides(AABB query);

    int size();
}
