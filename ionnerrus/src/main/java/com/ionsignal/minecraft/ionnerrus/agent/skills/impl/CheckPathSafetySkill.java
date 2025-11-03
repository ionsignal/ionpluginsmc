package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.AStarPathfinder;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;

import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;

/**
 * A skill to check if a path to a target and back is safe and not too long.
 * It calculates paths without moving the NPC and checks their total length.
 */
public class CheckPathSafetySkill implements Skill<Boolean> {
    private final Location targetLocation;
    private final Location returnLocation;
    private final double maxTotalPathLength;

    /**
     * Constructs a skill to check path safety.
     *
     * @param targetLocation     The destination location to check a path to.
     * @param returnLocation     The location to check a return path to from the target.
     * @param maxTotalPathLength The maximum combined geometric length of both paths.
     */
    public CheckPathSafetySkill(Location targetLocation, Location returnLocation, double maxTotalPathLength) {
        this.targetLocation = targetLocation;
        this.returnLocation = returnLocation;
        this.maxTotalPathLength = maxTotalPathLength;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        // Path 1: Agent's current location -> targetLocation
        CompletableFuture<java.util.Optional<Path>> pathToTargetFuture = AStarPathfinder.findPath(
                agent.getPersona().getLocation(),
                targetLocation,
                NavigationParameters.DEFAULT);

        // Chain the second pathfinding call to execute after the first one completes.
        return pathToTargetFuture.thenComposeAsync(pathToTargetOpt -> {
            if (pathToTargetOpt.isEmpty()) {
                // Path to target failed, no need to check the return path.
                return CompletableFuture.completedFuture(false);
            }

            Path pathToTarget = pathToTargetOpt.get();
            double lengthToTarget = pathToTarget.getLength();
            // If the first path alone is too long, we can fail early.
            if (lengthToTarget > maxTotalPathLength) {
                return CompletableFuture.completedFuture(false);
            }
            // Path 2: targetLocation -> returnLocation
            CompletableFuture<java.util.Optional<Path>> pathFromTargetFuture = AStarPathfinder.findPath(
                    targetLocation,
                    returnLocation,
                    NavigationParameters.DEFAULT);
            return pathFromTargetFuture.thenApply(pathFromTargetOpt -> {
                if (pathFromTargetOpt.isEmpty()) {
                    // Return path failed.
                    return false;
                }
                Path pathFromTarget = pathFromTargetOpt.get();
                double lengthFromTarget = pathFromTarget.getLength();
                // Check combined length against the maximum allowed.
                return (lengthToTarget + lengthFromTarget) <= maxTotalPathLength;
            });
        }, IonNerrus.getInstance().getOffloadThreadExecutor());
    }
}