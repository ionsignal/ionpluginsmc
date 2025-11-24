package com.ionsignal.minecraft.ionnerrus.persona.components;

import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;

/**
 * Controls spatial locomotion.
 * Only ONE movement operation can be active at a time.
 */
public interface MovementCapability {
    /**
     * Initiates navigation to a target location.
     *
     * @param target
     *            The destination location.
     * @return A future that completes with the result of the navigation.
     * @throws IllegalStateException
     *             if a movement is already active.
     */
    CompletableFuture<MovementResult> moveTo(Location target);

    /**
     * Initiates navigation along a pre-calculated path.
     *
     * @param path
     *            The path to follow.
     * @return A future that completes with the result of the navigation.
     */
    CompletableFuture<MovementResult> moveTo(Path path);

    /**
     * Engages (moves towards) a potentially dynamic entity target.
     *
     * @param target
     *            The entity to engage.
     * @param stopDistanceSquared
     *            The squared distance at which to stop.
     * @return A future that completes with the result of the engagement.
     */
    CompletableFuture<MovementResult> engage(Entity target, double stopDistanceSquared);

    /**
     * Cancels active movement (idempotent).
     * Completes the active future with CANCELLED result.
     */
    void stop();

    /**
     * Query: Is the entity currently in motion?
     * 
     * @return true if a movement operation is active.
     */
    boolean isMoving();
}