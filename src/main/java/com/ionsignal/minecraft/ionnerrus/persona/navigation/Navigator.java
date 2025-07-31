package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.MetadataKeys;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;

// DEBUG
import com.ionsignal.minecraft.ionnerrus.util.DebugPath;
// DEBUG

import net.minecraft.world.entity.ai.control.MoveControl;

import org.bukkit.Location;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

public class Navigator {
    private static final double WAYPOINT_REACHED_DISTANCE_SQUARED = 0.75 * 0.75;
    private static final float DEFAULT_MOVEMENT_SPEED = 1.0f;
    private static final int MAX_STATIONARY_TICKS = 60;

    private final Persona persona;
    private int stationaryTicks = 0;
    private Location lastTickLocation;
    private CompletableFuture<NavigationResult> navigationFuture;
    private Path currentPath;
    private Queue<Location> waypoints;

    public Navigator(Persona persona) {
        this.persona = persona;
    }

    // Overload existing method for backward compatibility
    public CompletableFuture<NavigationResult> navigateTo(Location target) {
        return navigateTo(target, null, NavigationParameters.DEFAULT);
    }

    // Overload existing method for backward compatibility
    public CompletableFuture<NavigationResult> navigateTo(Location target, NavigationParameters params) {
        return navigateTo(target, null, params);
    }

    // The new primary navigation method
    public CompletableFuture<NavigationResult> navigateTo(Location target, Location lookTarget, NavigationParameters params) {
        if (isNavigating()) {
            cancelNavigation(NavigationResult.CANCELLED);
        }
        if (lookTarget != null) {
            persona.getPersonaEntity().getLookControl().setLookAt(lookTarget.getX(), lookTarget.getY(), lookTarget.getZ());
        }

        // DEBUG
        DebugPath.logAreaAround(persona.getEntity().getLocation(), 4);
        // DEBUG
        this.navigationFuture = new CompletableFuture<>();
        AStarPathfinder.findPath(persona.getLocation(), target, params).thenAcceptAsync(pathOptional -> {
            if (pathOptional.isPresent() && !pathOptional.get().waypoints().isEmpty()) {
                this.currentPath = pathOptional.get();
                this.waypoints = new LinkedList<>(this.currentPath.waypoints());
                this.lastTickLocation = persona.getLocation();
                this.stationaryTicks = 0;
                // The A* path includes the start node so we remove it.
                if (!this.waypoints.isEmpty()) {
                    Location firstWaypoint = this.waypoints.peek();
                    // Use a 1-block 2D radius check to see if we are standing on the first waypoint.
                    if (distance2DSquared(persona.getLocation(), firstWaypoint) < 1.0) {
                        this.waypoints.poll(); // Discard the start node.
                    }
                }
                // If the path is now empty, we were already at the destination.
                if (this.waypoints.isEmpty()) {
                    finish(NavigationResult.SUCCESS);
                    return;
                }
            } else {
                finish(NavigationResult.UNREACHABLE);
            }
        }, persona.getManager().getPlugin().getMainThreadExecutor());
        return this.navigationFuture;
    }

    public void tick() {
        if (!isNavigating() || waypoints == null || waypoints.isEmpty() || !persona.isSpawned()) {
            return;
        }
        // Stuck detection
        Location currentLocation = persona.getLocation();
        if (lastTickLocation != null && currentLocation.distanceSquared(lastTickLocation) < 0.001) {
            stationaryTicks++;
            if (stationaryTicks > MAX_STATIONARY_TICKS) {
                finish(NavigationResult.STUCK);
                return;
            }
        } else {
            stationaryTicks = 0;
        }
        this.lastTickLocation = currentLocation.clone();
        // Peek waypoint and check 3D distance.
        Location nextWaypoint = waypoints.peek();
        if (currentLocation.distanceSquared(nextWaypoint) < WAYPOINT_REACHED_DISTANCE_SQUARED) {
            waypoints.poll();
            if (waypoints.isEmpty()) {
                finish(NavigationResult.SUCCESS);
                return;
            }
            nextWaypoint = waypoints.peek();
        }
        // Unified jump and swim-up logic
        PersonaEntity personaEntity = persona.getPersonaEntity();
        double dy = nextWaypoint.getY() - currentLocation.getY();
        // Trigger a "jump" if on land and needing to climb, or if in water and needing to ascend.
        // The underlying LivingEntity.travel() method uses the 'jumping' flag for both actions.
        boolean needsToAscend = (personaEntity.isInWater() && dy > 0.1) ||
                (!personaEntity.isInWater() && dy > personaEntity.maxUpStep());
        if (needsToAscend) {
            // Check if we are horizontally close enough to justify an ascent/jump.
            // A slightly larger radius than 1.0 gives a more natural feel to jumps.
            if (distance2DSquared(currentLocation, nextWaypoint) < 2.25) { // 1.5 block horizontal radius
                personaEntity.getJumpControl().jump();
            }
        }
        // Set speed and move attempting to change altitude...
        // By passing the waypoint's Y-level, we allow the controller to handle all vertical aspects of movement correctly.
        MoveControl moveControl = persona.getPersonaEntity().getMoveControl();
        float speed = persona.getMetadata().get(MetadataKeys.MOVEMENT_SPEED, Double.class)
                .orElse((double) DEFAULT_MOVEMENT_SPEED).floatValue();
        moveControl.setWantedPosition(nextWaypoint.getX(), nextWaypoint.getY(), nextWaypoint.getZ(), speed);
    }

    public void cancelNavigation(NavigationResult reason) {
        finish(reason);
        PersonaEntity personaEntity = persona.getPersonaEntity();
        if (personaEntity != null && personaEntity.isAlive()) {
            personaEntity.getMoveControl().setWantedPosition(
                    personaEntity.getX(), personaEntity.getY(), personaEntity.getZ(), 0);
        }
    }

    private void finish(NavigationResult result) {
        if (navigationFuture != null && !navigationFuture.isDone()) {
            navigationFuture.complete(result);
        }
        if (persona.isSpawned()) {
            persona.getPersonaEntity().getLookControl().stopLooking();
        }
        this.currentPath = null;
        this.waypoints = null;
        this.stationaryTicks = 0;
        this.lastTickLocation = null;
    }

    private double distance2DSquared(Location loc1, Location loc2) {
        double dx = loc1.getX() - loc2.getX();
        double dz = loc1.getZ() - loc2.getZ();
        return dx * dx + dz * dz;
    }

    public boolean isNavigating() {
        return navigationFuture != null && !navigationFuture.isDone();
    }

    public Path getCurrentPath() {
        return currentPath;
    }
}