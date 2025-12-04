package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.MetadataKeys;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.LocomotionController;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.ManeuverResult;
import com.ionsignal.minecraft.ionnerrus.util.DebugPath;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import com.google.common.base.Preconditions;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class Navigator {
    private static final boolean DEBUG_VISUALIZATION = true;

    // Movement constants
    private static final float DEFAULT_MOVEMENT_SPEED = 1.0f;

    // Stuck check constants
    private static final int STUCK_TIME_THRESHOLD_TICKS = 40;
    private static final int STUCK_CHECK_INTERVAL_TICKS = 10;
    private static final double STUCK_DISTANCE_THRESHOLD = 0.3;
    private static final double STUCK_DISTANCE_THRESHOLD_SQUARED = STUCK_DISTANCE_THRESHOLD * STUCK_DISTANCE_THRESHOLD;

    // Deceleration constants
    private static final float MIN_DECELERATION_SPEED = 0.08f;
    private static final double DECELERATION_DISTANCE = 1.5;
    private static final double DECELERATION_DISTANCE_SQUARED = DECELERATION_DISTANCE * DECELERATION_DISTANCE;

    // Repath constants
    private static final double FOLLOW_DISTANCE_THRESHOLD_SQUARED = 144.0;

    private final Persona persona;
    private final LocomotionController locomotion;

    private enum State {
        IDLE, PATH_FOLLOWING, ENGAGING, FOLLOWING
    }

    private State state = State.IDLE;

    // State
    private CompletableFuture<MovementResult> activeFuture;

    // Path Following State
    private Path currentPath;
    private PathFollower pathFollower;
    private boolean isPathfinding = false;

    // Engaging State
    private Entity engageTarget;
    private double engageProximitySquared;
    private Location stuckCheckLocation;
    private int ticksSinceStuckCheck;
    private int totalTicksStuck;

    // Following State
    private Entity followTarget;
    private double followDistanceSquared;
    private double stopDistanceSquared;
    private Path currentFollowPath;

    public Navigator(Persona persona, LocomotionController locomotion) {
        this.persona = persona;
        this.locomotion = locomotion;
    }

    // Overload existing method for backward compatibility
    public CompletableFuture<MovementResult> navigateTo(Location target, ExecutionToken token) {
        return navigateTo(target, NavigationParameters.DEFAULT, token);
    }

    // The new primary navigation method
    public CompletableFuture<MovementResult> navigateTo(Location target, NavigationParameters params, ExecutionToken token) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "Async navigation access detected!");
        if (isBusy()) {
            cancelCurrentOperation(MovementResult.CANCELLED);
        }
        state = State.PATH_FOLLOWING;
        activeFuture = new CompletableFuture<>();
        resetStuckDetection();
        // Initiate pathfinding
        AStarPathfinder.findPath(persona.getLocation(), target, params, token)
                .whenCompleteAsync((pathOptional, ex) -> {
                    if (state != State.PATH_FOLLOWING) {
                        return;
                    }
                    if (ex != null) {
                        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                        if (!(cause instanceof CancellationException)) {
                            persona.getManager().getPlugin().getLogger().warning("Pathfinding failed: " + cause.getMessage());
                            cause.printStackTrace();
                        }
                        finishPathing(cause instanceof CancellationException ? MovementResult.CANCELLED : MovementResult.FAILURE);
                        return;
                    }
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
        return activeFuture;
    }

    public CompletableFuture<MovementResult> navigateTo(Path path, ExecutionToken token) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "Async navigation access detected!");
        if (isBusy()) {
            cancelCurrentOperation(MovementResult.CANCELLED);
        }
        state = State.PATH_FOLLOWING;
        activeFuture = new CompletableFuture<>();
        resetStuckDetection();
        // Directly use the provided path, skipping the pathfinding step.
        if (path != null && !path.isEmpty()) {
            currentPath = path;
            pathFollower = new PathFollower(currentPath);
        } else {
            // Complete immediately if path is invalid
            finishPathing(MovementResult.UNREACHABLE);
        }
        return activeFuture;
    }

    public CompletableFuture<MovementResult> engageOn(Entity target, double proximityDistanceSquared, ExecutionToken token) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "Async navigation access detected!");
        if (isBusy()) {
            cancelCurrentOperation(MovementResult.CANCELLED);
        }
        resetStuckDetection();
        state = State.ENGAGING;
        engageTarget = target;
        engageProximitySquared = proximityDistanceSquared;
        activeFuture = new CompletableFuture<>();
        return activeFuture;
    }

    public CompletableFuture<MovementResult> followOn(Entity target, double followDistance, double stopDistance, ExecutionToken token) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "Async navigation access detected!");
        if (isBusy()) {
            cancelCurrentOperation(MovementResult.CANCELLED);
        }
        state = State.FOLLOWING;
        activeFuture = new CompletableFuture<>();
        // Reset stuck
        resetStuckDetection();
        // Store instance vars
        this.followTarget = target;
        this.followDistanceSquared = followDistance * followDistance;
        this.stopDistanceSquared = stopDistance * stopDistance;
        this.currentFollowPath = null;
        if (this.pathFollower != null) {
            this.pathFollower.cleanup();
            this.pathFollower = null;
        }
        this.isPathfinding = true;
        Location agentLocation = persona.getLocation();
        Location targetLocation = followTarget.getLocation();
        AStarPathfinder.findPath(agentLocation, targetLocation, NavigationParameters.DEFAULT, token)
                .whenCompleteAsync((pathOptional, ex) -> {
                    this.isPathfinding = false;
                    if (state != State.FOLLOWING) {
                        return;
                    }
                    if (ex != null) {
                        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                        if (!(cause instanceof CancellationException)) {
                            persona.getManager().getPlugin().getLogger().warning("Follow pathfinding failed: " + cause.getMessage());
                        }
                        finishPathing(cause instanceof CancellationException ? MovementResult.CANCELLED : MovementResult.FAILURE);
                        return;
                    }
                    if (pathOptional.isPresent() && !pathOptional.get().isEmpty()) {
                        currentFollowPath = pathOptional.get();
                        if (DEBUG_VISUALIZATION) {
                            DebugVisualizer.displayPath(currentFollowPath, 20);
                        }
                        pathFollower = new PathFollower(currentFollowPath);
                    } else {
                        finishPathing(MovementResult.UNREACHABLE);
                    }
                }, persona.getManager().getPlugin().getMainThreadExecutor());
        return activeFuture;
    }

    public void tick() {
        if (!persona.isSpawned()) {
            if (isBusy()) {
                cancelCurrentOperation(MovementResult.CANCELLED);
            }
            return;
        }
        // Check for Maneuver Handoff (Explicit Result Consumption)
        Optional<ManeuverResult> maneuverResult = locomotion.popLastResult();
        if (maneuverResult.isPresent()) {
            ManeuverResult result = maneuverResult.get();
            if (result.status() == ManeuverResult.Status.SUCCESS) {
                // Maneuver succeeded. Snap path follower to the next segment.
                if (pathFollower != null && (state == State.PATH_FOLLOWING || state == State.FOLLOWING)) {
                    Path activePath = (state == State.FOLLOWING) ? currentFollowPath : currentPath;
                    if (activePath != null) {
                        // We assume the maneuver brought us to the end of the current segment.
                        // We snap to the NEXT node's distance.
                        double currentDist = pathFollower.getCurrentDist();
                        double nextNodeDist = activePath.getNextNodeDistance(currentDist + 0.1);
                        pathFollower.snapToSegment(nextNodeDist);
                        // Reset stuck detection because a maneuver just finished successfully
                        resetStuckDetection();
                    }
                }
            } else {
                // Maneuver failed (timeout or physics issue)
                log();
                persona.getManager().getPlugin().getLogger().warning("Maneuver failed: " + result.message());
                finishPathing(MovementResult.STUCK);
                return;
            }
        }
        // If maneuvering, skip all steering logic because the maneuver has total control of the physics.
        if (locomotion.isManeuvering()) {
            return;
        }
        // Check for geometric deviation (Tether Snap)
        if (state == State.PATH_FOLLOWING && pathFollower != null && pathFollower.isOffPath()) {
            log();
            persona.getManager().getPlugin().getLogger().warning("Persona " + persona.getName() + " deviated from path (Tether Snap).");
            finishPathing(MovementResult.STUCK);
            return;
        }
        // Standard Stuck detection (Temporal)
        if (isBusy() && !isPathfinding && checkIfStuck()) {
            log();
            persona.getManager().getPlugin().getLogger()
                    .warning("Persona " + persona.getName() + " is stuck (Temporal). Failing current task.");
            finishPathing(MovementResult.STUCK);
            return;
        }
        switch (state) {
            case IDLE:
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
        Location currentLocation = persona.getLocation();
        pathFollower.updateState(currentLocation);
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
            finishPathing(MovementResult.TARGET_LOST);
            return;
        }
        Location agentLocation = persona.getLocation();
        if (agentLocation.distanceSquared(engageTarget.getLocation()) < engageProximitySquared) {
            finishPathing(MovementResult.SUCCESS);
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
            finishPathing(MovementResult.TARGET_LOST);
            return;
        }
        if (isPathfinding) {
            // wait for pathfinder
            return;
        }
        Location agentLocation = persona.getLocation();
        Location targetLocation = followTarget.getLocation();
        double distanceSq = agentLocation.distanceSquared(targetLocation);
        if (distanceSq < stopDistanceSquared) {
            // Use locomotion stop instead of direct entity access
            locomotion.stop();
            // Reset stuck detection to prevent false positives
            resetStuckDetection();
            currentFollowPath = null;
            if (this.pathFollower != null) {
                this.pathFollower.cleanup();
                this.pathFollower = null;
            }
        } else if (distanceSq < followDistanceSquared) {
            SteeringResult result = new SteeringResult(targetLocation, SteeringResult.MovementType.WALK);
            locomotion.drive(result, getSpeed());
            currentFollowPath = null;
            if (this.pathFollower != null) {
                this.pathFollower.cleanup();
                this.pathFollower = null;
            }
        } else {
            // Target moved from path end
            boolean needsRepath = currentFollowPath == null || pathFollower == null || pathFollower.isFinished(agentLocation) ||
                    currentFollowPath.getPointAtDistance(currentFollowPath.getLength())
                            .distanceSquared(targetLocation) > FOLLOW_DISTANCE_THRESHOLD_SQUARED;
            if (needsRepath) {
                finishPathing(MovementResult.REPATH_NEEDED);
                return;
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
            case ENGAGING:
            case FOLLOWING:
                finishPathing(reason);
                break;
            case IDLE:
                break;
        }
    }

    private void finishPathing(MovementResult result) {
        if (activeFuture != null && !activeFuture.isDone()) {
            activeFuture.complete(result);
        }
        reset();
    }

    private void reset() {
        locomotion.stop();
        this.isPathfinding = false;
        this.currentPath = null;
        if (this.pathFollower != null) {
            this.pathFollower.cleanup();
            this.pathFollower = null;
        }
        this.activeFuture = null;
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