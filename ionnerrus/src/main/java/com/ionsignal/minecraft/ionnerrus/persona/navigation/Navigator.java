package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.MetadataKeys;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.LocomotionController;
import com.ionsignal.minecraft.ionnerrus.util.DebugPath;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;

public class Navigator {
    private static final boolean DEBUG_VISUALIZATION = true;

    // Movement constants
    private static final float DEFAULT_MOVEMENT_SPEED = 1.0f;

    // Stuck check constants
    private static final int STUCK_TIME_THRESHOLD_TICKS = 40;
    private static final int STUCK_CHECK_INTERVAL_TICKS = 10;
    private static final double STUCK_DISTANCE_THRESHOLD_SQUARED = 0.25;

    // Deceleration constants
    private static final double DECELERATION_DISTANCE_SQUARED = 1.5 * 1.5;
    private static final float MIN_DECELERATION_SPEED = 0.1f;

    // Repath constants
    private static final double FOLLOW_DISTANCE_THRESHOLD_SQUARED = 50.0;
    private static final int REPATH_INTERVAL_TICKS = 20;

    private final Persona persona;
    private final LocomotionController locomotion;

    private enum State {
        IDLE, PATH_FOLLOWING, ENGAGING, FOLLOWING
    }

    private State state = State.IDLE;

    // Path Following State
    private Path currentPath;
    private PathFollower pathFollower;
    private CompletableFuture<MovementResult> navigationFuture;

    // Engaging State
    private Entity engageTarget;
    private CompletableFuture<MovementResult> engageFuture;
    private double engageProximitySquared;
    private Location stuckCheckLocation;
    private int ticksSinceStuckCheck;
    private int totalTicksStuck;

    // Following State
    private Entity followTarget;
    private double followDistanceSquared;
    private double stopDistanceSquared;
    private Path currentFollowPath;
    private int ticksUntilNextRepath;

    public Navigator(Persona persona, LocomotionController locomotion) {
        this.persona = persona;
        this.locomotion = locomotion;
    }

    // Overload existing method for backward compatibility
    public CompletableFuture<MovementResult> navigateTo(Location target) {
        return navigateTo(target, NavigationParameters.DEFAULT);
    }

    // The new primary navigation method
    public CompletableFuture<MovementResult> navigateTo(Location target, NavigationParameters params) {
        if (isBusy()) {
            cancelCurrentOperation(MovementResult.CANCELLED);
        }
        state = State.PATH_FOLLOWING;
        navigationFuture = new CompletableFuture<>();
        resetStuckDetection();
        // Initiate pathfinding
        AStarPathfinder.findPath(persona.getLocation(), target, params).thenAcceptAsync(pathOptional -> {
            if (pathOptional.isPresent() && !pathOptional.get().isEmpty()) {
                currentPath = pathOptional.get();
                if (DEBUG_VISUALIZATION) {
                    DebugVisualizer.displayPath(currentPath, 30);
                }
                pathFollower = new PathFollower(currentPath);
            } else {
                finishPathing(MovementResult.UNREACHABLE);
            }
        }, persona.getManager().getPlugin().getMainThreadExecutor());
        return navigationFuture;
    }

    public CompletableFuture<MovementResult> navigateTo(Path path) {
        if (isBusy()) {
            cancelCurrentOperation(MovementResult.CANCELLED);
        }
        state = State.PATH_FOLLOWING;
        navigationFuture = new CompletableFuture<>();
        resetStuckDetection();
        // Directly use the provided path, skipping the pathfinding step.
        if (path != null && !path.isEmpty()) {
            // This needs to run on the main thread to safely set the path follower.
            persona.getManager().getPlugin().getMainThreadExecutor().execute(() -> {
                currentPath = path;
                pathFollower = new PathFollower(currentPath);
            });
        } else {
            // Complete immediately if path is invalid
            finishPathing(MovementResult.UNREACHABLE);
        }
        return navigationFuture;
    }

    public CompletableFuture<MovementResult> engageOn(Entity target, double proximityDistanceSquared) {
        if (isBusy()) {
            cancelCurrentOperation(MovementResult.CANCELLED);
        }
        resetStuckDetection();
        state = State.ENGAGING;
        engageTarget = target;
        engageProximitySquared = proximityDistanceSquared;
        engageFuture = new CompletableFuture<>();
        return engageFuture;
    }

    public CompletableFuture<MovementResult> followOn(Entity target, double followDistance, double stopDistance) {
        if (isBusy()) {
            cancelCurrentOperation(MovementResult.CANCELLED);
        }
        state = State.FOLLOWING;
        navigationFuture = new CompletableFuture<>();
        // Reset stuck
        resetStuckDetection();
        // Store instance vars
        this.followTarget = target;
        this.followDistanceSquared = followDistance * followDistance;
        this.stopDistanceSquared = stopDistance * stopDistance;
        this.currentFollowPath = null;
        this.pathFollower = null;
        this.ticksUntilNextRepath = 0;
        return navigationFuture;
    }

    public void tick() {
        if (!persona.isSpawned()) {
            if (isBusy()) {
                cancelCurrentOperation(MovementResult.CANCELLED);
            }
            return;
        }
        if (locomotion.isBlocked()) {
            locomotion.clearBlocked();
            persona.getManager().getPlugin().getLogger()
                    .warning("Persona " + persona.getName() + " collision detected (Bumper). Reporting Stuck.");
            finishPathing(MovementResult.STUCK);
            return;
        }
        // PHASE 3 UPDATE: Check for geometric deviation (Tether Snap)
        if (state == State.PATH_FOLLOWING && pathFollower != null && pathFollower.isOffPath()) {
            persona.getManager().getPlugin().getLogger().warning("Persona " + persona.getName() + " deviated from path (Tether Snap).");
            log();
            finishPathing(MovementResult.STUCK);
            return;
        }
        // Standard Stuck detection (Temporal)
        // We pause stuck detection if the LocomotionController is performing a Maneuver (Jump/Drop) because
        // maneuvers have their own internal timeouts, and often require special timeout handling
        if (isBusy() && !locomotion.isManeuvering() && checkIfStuck()) {
            log();
            persona.getManager().getPlugin().getLogger()
                    .warning("Persona " + persona.getName() + " is stuck (Temporal). Failing current task.");
            if (state == State.PATH_FOLLOWING) {
                finishPathing(MovementResult.STUCK);
            } else if (state == State.ENGAGING) {
                finishEngaging(MovementResult.STUCK);
            } else if (state == State.FOLLOWING) {
                finishFollowing(MovementResult.STUCK);
            }
            return; // Stop further processing this tick.
        }
        switch (state) {
            case IDLE:
                // Do nothing
                break;
            case PATH_FOLLOWING:
                tickPathFollowing();
                break;
            case ENGAGING:
                tickEngaging();
                break;
            case FOLLOWING:
                tickFollowing();
                break;
        }
    }

    private void tickPathFollowing() {
        if (pathFollower == null) {
            return;
        }
        // Check if blocked by dynamic obstacle
        if (locomotion.isBlocked()) {
            persona.getManager().getPlugin().getLogger().warning("Persona " + persona.getName() + " collision detected (Bumper).");
            // Immediately triggers stuck
            finishPathing(MovementResult.STUCK);
            return;
        }
        Location currentLocation = persona.getLocation();
        if (pathFollower.isFinished(currentLocation)) {
            // Only finish pathing if the state is actually PATH_FOLLOWING.
            if (state == State.PATH_FOLLOWING) {
                finishPathing(MovementResult.SUCCESS);
            }
            return;
        }
        // Delegate steering calculation to PathFollower and execution to LocomotionController
        SteeringResult result = pathFollower.calculateSteering(currentLocation);
        float currentSpeed = getSpeed();
        // Apply deceleration logic
        if (result.movementType() == SteeringResult.MovementType.WALK) {
            Path pathToFollow = (state == State.FOLLOWING) ? currentFollowPath : currentPath;
            if (pathToFollow != null) {
                // Use new spline length for accurate deceleration
                double distRemaining = pathToFollow.getLength() - pathFollower.getCurrentDist();
                // We use the square root of the constant because distRemaining is linear.
                // Constant is 1.5 * 1.5, so threshold is 1.5 meters.
                double decelerationThreshold = Math.sqrt(DECELERATION_DISTANCE_SQUARED);
                if (distRemaining < decelerationThreshold) {
                    double speedScale = distRemaining / decelerationThreshold;
                    currentSpeed = (float) Math.max(MIN_DECELERATION_SPEED, currentSpeed * speedScale);
                }
            }
        }
        // Send intent to LocomotionController
        locomotion.drive(result, currentSpeed);
    }

    private void tickEngaging() {
        // Check if target is still valid
        if (engageTarget == null || engageTarget.isDead()) {
            finishEngaging(MovementResult.TARGET_LOST);
            return;
        }
        Location agentLocation = persona.getLocation();
        if (agentLocation.distanceSquared(engageTarget.getLocation()) < engageProximitySquared) {
            finishEngaging(MovementResult.SUCCESS);
            return;
        }
        // Dampen speed as we get closer for a smoother stop
        Location targetLocation = engageTarget.getLocation();
        double distance = agentLocation.distance(targetLocation);
        float speed = getSpeed();
        if (distance < 2.0) {
            speed *= (distance / 2.0);
        }
        // Send direct steering intent
        SteeringResult result = new SteeringResult(targetLocation, SteeringResult.MovementType.WALK);
        locomotion.drive(result, Math.max(speed, 0.1f));
    }

    private void tickFollowing() {
        if (followTarget == null || followTarget.isDead()) {
            finishFollowing(MovementResult.TARGET_LOST);
            return;
        }
        Location agentLocation = persona.getLocation();
        Location targetLocation = followTarget.getLocation();
        double distanceSq = agentLocation.distanceSquared(targetLocation);
        if (distanceSq < stopDistanceSquared) {
            // Use locomotion stop instead of direct entity access
            locomotion.stop();
            currentFollowPath = null;
            pathFollower = null;
        } else if (distanceSq < followDistanceSquared) {
            SteeringResult result = new SteeringResult(targetLocation, SteeringResult.MovementType.WALK);
            locomotion.drive(result, getSpeed());
            currentFollowPath = null;
            pathFollower = null;
        } else {
            // Pathfinding Zone
            ticksUntilNextRepath--;
            // Target moved from path end
            boolean needsRepath = currentFollowPath == null || pathFollower == null ||
                    pathFollower.isFinished(agentLocation) ||
                    ticksUntilNextRepath <= 0 ||
                    currentFollowPath.getPointAtDistance(currentFollowPath.getLength())
                            .distanceSquared(targetLocation) > FOLLOW_DISTANCE_THRESHOLD_SQUARED;
            if (needsRepath) {
                ticksUntilNextRepath = REPATH_INTERVAL_TICKS;
                // Use helper to find ground
                // Note: findGround in helper needs update or we use AStarPathfinder's internal logic
                // For now, assume targetLocation is valid
                AStarPathfinder.findPath(agentLocation, targetLocation, NavigationParameters.DEFAULT)
                        .thenAcceptAsync(pathOpt -> {
                            if (state != State.FOLLOWING)
                                return; // State changed while pathfinding
                            if (pathOpt.isPresent() && !pathOpt.get().isEmpty()) {
                                currentFollowPath = pathOpt.get();
                                if (DEBUG_VISUALIZATION) {
                                    DebugVisualizer.displayPath(currentFollowPath, REPATH_INTERVAL_TICKS + 5);
                                }
                                pathFollower = new PathFollower(currentFollowPath);
                            } else {
                                currentFollowPath = null; // Path failed, will try again
                                pathFollower = null;
                            }
                        }, persona.getManager().getPlugin().getMainThreadExecutor());
            }
            if (pathFollower != null && currentFollowPath != null) {
                tickPathFollowing(); // Reuse existing path following logic
            }
        }
    }

    /**
     * Checks if the persona is stuck by monitoring its movement over time.
     * 
     * @return true if the persona is considered stuck, false otherwise.
     */
    private boolean checkIfStuck() {
        if (state == State.IDLE) {
            totalTicksStuck = 0;
            return false;
        }
        ticksSinceStuckCheck++;
        if (ticksSinceStuckCheck >= STUCK_CHECK_INTERVAL_TICKS) {
            Location currentPos = persona.getLocation();
            double dx = currentPos.getX() - stuckCheckLocation.getX();
            double dz = currentPos.getZ() - stuckCheckLocation.getZ();
            if ((dx * dx + dz * dz) < STUCK_DISTANCE_THRESHOLD_SQUARED) {
                totalTicksStuck += ticksSinceStuckCheck;
            } else {
                // We made horizontal progress, reset stuck counter
                totalTicksStuck = 0;
            }
            stuckCheckLocation = currentPos;
            ticksSinceStuckCheck = 0;
            if (totalTicksStuck >= STUCK_TIME_THRESHOLD_TICKS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resets all variables related to stuck detection.
     * Called whenever a new navigation or engage operation begins.
     */
    private void resetStuckDetection() {
        this.stuckCheckLocation = persona.getLocation();
        this.ticksSinceStuckCheck = 0;
        this.totalTicksStuck = 0;
    }

    public void cancelCurrentOperation(MovementResult reason) {
        switch (state) {
            case PATH_FOLLOWING:
                finishPathing(reason);
                break;
            case ENGAGING:
                finishEngaging(reason);
                break;
            case FOLLOWING:
                finishFollowing(reason);
                break;
            case IDLE:
                break;
        }
    }

    private void finishPathing(MovementResult result) {
        if (navigationFuture != null && !navigationFuture.isDone()) {
            navigationFuture.complete(result);
        }
        reset();
    }

    public void finishEngaging(MovementResult result) {
        if (engageFuture != null && !engageFuture.isDone()) {
            engageFuture.complete(result);
        }
        reset();
    }

    private void finishFollowing(MovementResult result) {
        if (navigationFuture != null && !navigationFuture.isDone()) {
            navigationFuture.complete(result);
        }
        reset();
    }

    private void reset() {
        locomotion.stop();
        this.navigationFuture = null;
        this.currentPath = null;
        this.pathFollower = null;
        this.engageFuture = null;
        this.engageTarget = null;
        this.followTarget = null;
        this.currentFollowPath = null;
        this.state = State.IDLE;
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public Path getCurrentPath() {
        return currentPath;
    }

    private void log() {
        Location currentPos = persona.getLocation();
        StringBuilder sb = new StringBuilder();
        sb.append("Persona ").append(persona.getName()).append(" is stuck! Failing current task.\n");
        sb.append(String.format("  - Current Location: (%s, %.2f, %.2f, %.2f)\n",
                currentPos.getWorld().getName(), currentPos.getX(), currentPos.getY(), currentPos.getZ()));
        if (state == State.PATH_FOLLOWING && currentPath != null && pathFollower != null && !currentPath.isEmpty()) {
            sb.append("  - Following Path (").append(currentPath.size()).append(" points total)\n");
        }
        persona.getManager().getPlugin().getLogger().warning(sb.toString());
        // Log a 5-block radius area scan to the console for visual debugging.
        DebugPath.logAreaAround(currentPos, 5);
    }

    /**
     * Retrieves the persona's configured movement speed.
     */
    private float getSpeed() {
        return persona.getMetadata().get(MetadataKeys.MOVEMENT_SPEED, Double.class)
                .orElse((double) DEFAULT_MOVEMENT_SPEED).floatValue();
    }
}