package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.MetadataKeys;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;

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
        this.navigationFuture = new CompletableFuture<>();
        AStarPathfinder.findPath(persona.getLocation(), target, params).thenAcceptAsync(pathOptional -> {
            if (pathOptional.isPresent() && !pathOptional.get().getSmoothedPoints().isEmpty()) {
                this.currentPath = pathOptional.get();
                // this.waypoints = new LinkedList<>(this.currentPath.getSmoothedPoints()); // smoothing
                this.waypoints = new LinkedList<>(this.currentPath.waypoints()); // no smoothing
                this.lastTickLocation = persona.getLocation();
                this.stationaryTicks = 0;
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
        // stuck detection
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
        // peek waypoint
        Location nextWaypoint = waypoints.peek();
        if (distance2DSquared(currentLocation, nextWaypoint) < WAYPOINT_REACHED_DISTANCE_SQUARED) {
            waypoints.poll();
            if (waypoints.isEmpty()) {
                finish(NavigationResult.SUCCESS);
                return;
            }
            nextWaypoint = waypoints.peek();
        }
        // jump
        double dy = nextWaypoint.getY() - currentLocation.getY();
        float stepHeight = (float) persona.getPersonaEntity().maxUpStep();
        if (dy > stepHeight) {
            if (distance2DSquared(currentLocation, nextWaypoint) < 1.0) {
                persona.getPersonaEntity().getJumpControl().jump();
            }
        }
        // move
        MoveControl moveControl = persona.getPersonaEntity().getMoveControl();
        float speed = persona.getMetadata().get(MetadataKeys.MOVEMENT_SPEED, Double.class)
                .orElse((double) DEFAULT_MOVEMENT_SPEED).floatValue();
        moveControl.setWantedPosition(nextWaypoint.getX(), currentLocation.getY(), nextWaypoint.getZ(), speed);
    }

    public void cancelNavigation(NavigationResult reason) {
        finish(reason);
        PersonaEntity personaEntity = persona.getPersonaEntity();
        personaEntity.getMoveControl().setWantedPosition(
                personaEntity.getX(), personaEntity.getY(), personaEntity.getZ(), 0);
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