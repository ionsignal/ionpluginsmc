package com.ionsignal.minecraft.ionnerrus.persona.components;

import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.LookResult;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;

/**
 * Controls head/body rotation.
 * Key invariant: Look targets can overlap with movement.
 */
public interface OrientationCapability {
    /**
     * Rotates head (and optionally body) toward a static location.
     *
     * Convergence: Completes when within 2 degrees of target.
     * Timeout: If convergence takes >60 ticks, completes with TIMEOUT.
     *
     * @param target
     *            The location to look at.
     * @param turnBody
     *            If true, the body will also rotate to face the target.
     * @param token
     *            The execution token bound to the lifecycle of the request.
     * @return A future that completes when the look operation finishes.
     */
    CompletableFuture<LookResult> lookAt(Location target, boolean turnBody, ExecutionToken token);

    /**
     * Rotates head (and optionally body) toward a static location.
     * Fire-and-forget version. Does not return a future.
     *
     * @param target
     *            The location to look at.
     * @param turnBody
     *            If true, the body will also rotate to face the target.
     * @param token
     *            The execution token bound to the lifecycle of the request.
     */
    void face(Location target, boolean turnBody, ExecutionToken token);

    /**
     * Tracks a moving entity indefinitely until cleared or overridden.
     * Fire-and-forget version.
     *
     * @param target
     *            The entity to track.
     * @param token
     *            The execution token bound to the lifecycle of the request.
     */
    void face(Entity target, ExecutionToken token);

    /**
     * Stops tracking and returns to neutral pose.
     */
    void clearLookTarget();
}