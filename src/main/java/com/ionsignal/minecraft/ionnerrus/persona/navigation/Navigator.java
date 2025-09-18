package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.MetadataKeys;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.EngageResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.movement.PersonaMoveControl;
import com.ionsignal.minecraft.ionnerrus.util.DebugPath;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;

public class Navigator {
    // Movement constants
    private static final float DEFAULT_MOVEMENT_SPEED = 1.0f;

    // Stuck check constants
    private static final int STUCK_TIME_THRESHOLD_TICKS = 40;
    private static final int STUCK_CHECK_INTERVAL_TICKS = 10;
    private static final double STUCK_DISTANCE_THRESHOLD_SQUARED = 0.25; // 0.1 * 0.1 blocks

    // Deceleration constants
    private static final double DECELERATION_DISTANCE_SQUARED = 1.5 * 1.5; // Start slowing down 2 blocks away
    private static final float MIN_DECELERATION_SPEED = 0.1f; // Don't slow down to a crawl

    private final Persona persona;

    private enum State {
        IDLE, PATH_FOLLOWING, ENGAGING, FOLLOWING
    }

    private State state = State.IDLE;

    // Path Following State
    private CompletableFuture<NavigationResult> navigationFuture;
    private Path currentPath;
    private PathFollower pathFollower;

    // Engaging State
    private Entity engageTarget;
    private CompletableFuture<EngageResult> engageFuture;
    private double engageProximitySquared;
    private Location stuckCheckLocation;
    private int ticksSinceStuckCheck;
    private int totalTicksStuck;

    // New fields for Following State
    private Entity followTarget;
    private double followDistanceSquared;
    private double stopDistanceSquared;
    private Path currentFollowPath;
    private int ticksUntilNextRepath;
    private static final int REPATH_INTERVAL_TICKS = 20; // Repath every second if needed

    private enum JumpState {
        NONE, PREPARING, JUMPING, ASCENDING, DESCENDING, DROPPING, EXITING_WATER
    }

    private JumpState jumpState = JumpState.NONE;
    private int jumpStateTicks = 0;
    private double jumpStartY = 0;
    private Location jumpTargetWaypoint = null;

    public Navigator(Persona persona) {
        this.persona = persona;
    }

    // Overload existing method for backward compatibility
    public CompletableFuture<NavigationResult> navigateTo(Location target) {
        return navigateTo(target, NavigationParameters.DEFAULT);
    }

    // The new primary navigation method
    public CompletableFuture<NavigationResult> navigateTo(Location target, NavigationParameters params) {
        if (isBusy()) {
            cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
        }
        state = State.PATH_FOLLOWING;
        navigationFuture = new CompletableFuture<>();
        resetStuckDetection();
        // Initiate pathfinding
        AStarPathfinder.findPath(persona.getLocation(), target, params).thenAcceptAsync(pathOptional -> {
            if (pathOptional.isPresent() && !pathOptional.get().isEmpty()) {
                currentPath = pathOptional.get();
                pathFollower = new PathFollower(currentPath);
            } else {
                // Call new finishPathing method
                finishPathing(NavigationResult.UNREACHABLE);
            }
        }, persona.getManager().getPlugin().getMainThreadExecutor());
        return navigationFuture;
    }

    public CompletableFuture<NavigationResult> navigateTo(Path path) {
        if (isBusy()) {
            cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
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
            finishPathing(NavigationResult.UNREACHABLE);
        }
        return navigationFuture;
    }

    public CompletableFuture<EngageResult> engageOn(Entity target, double proximityDistanceSquared) {
        if (isBusy()) {
            cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
        }
        resetStuckDetection();
        state = State.ENGAGING;
        engageTarget = target;
        engageProximitySquared = proximityDistanceSquared;
        engageFuture = new CompletableFuture<>();
        return engageFuture;
    }

    public CompletableFuture<NavigationResult> followOn(Entity target, double followDistance, double stopDistance) {
        if (isBusy()) {
            cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
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
        if (!persona.isSpawned() || persona.isInventoryLocked()) {
            if (isBusy()) {
                cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
            }
            return;
        }
        // Stuck detection runs for any active state.
        if (isBusy() && checkIfStuck()) {
            log();
            persona.getManager().getPlugin().getLogger().warning("Persona " + persona.getName() + " is stuck! Failing current task.");
            if (state == State.PATH_FOLLOWING) {
                finishPathing(NavigationResult.STUCK);
            } else if (state == State.ENGAGING) {
                finishEngaging(EngageResult.STUCK);
            } else if (state == State.FOLLOWING) {
                finishFollowing(NavigationResult.STUCK);
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
        Location currentLocation = persona.getLocation();
        if (pathFollower.isFinished(currentLocation)) {
            // CHANGE: Only finish pathing if the state is actually PATH_FOLLOWING.
            // This allows tickFollowing to reuse this method without ending the operation.
            if (state == State.PATH_FOLLOWING) {
                finishPathing(NavigationResult.SUCCESS);
            }
            return;
        }
        // Handle jump state machine first
        if (jumpState != JumpState.NONE) {
            handleJumpNavigation();
            return;
        }
        SteeringResult result = pathFollower.calculateSteering(currentLocation);
        switch (result.movementType()) {
            case JUMP:
                // The PathFollower has detected an upcoming jump.
                // Initiate the jump sequence.
                jumpState = JumpState.PREPARING;
                jumpStateTicks = 0;
                jumpStartY = persona.getLocation().getY();
                jumpTargetWaypoint = result.target(); // The target after the jump
                handleJumpNavigation();
                break;
            case DROP:
                // The PathFollower has detected a drop.
                // Initiate a controlled descent by starting the state machine at the descending phase.
                jumpTargetWaypoint = result.target(); // The landing spot.
                jumpState = JumpState.DROPPING;
                jumpStateTicks = 0;
                // Immediately call handleJumpNavigation to apply forward momentum as we walk off the ledge.
                handleJumpNavigation();
                break;
            case SWIM:
                applySwimMovement(result.target(), result.verticalDirection());
                break;
            case WALK:
                // Apply steering using the correct path object depending on the current navigation state
                float currentSpeed = getSpeed();
                Path pathToFollow = (state == State.FOLLOWING) ? currentFollowPath : currentPath;
                if (pathToFollow != null) {
                    Location finalDestination = pathToFollow.getPoint(pathToFollow.size() - 1);
                    double distanceToFinalSq = currentLocation.distanceSquared(finalDestination);
                    if (distanceToFinalSq < DECELERATION_DISTANCE_SQUARED) {
                        // Apply the scaling to the base speed, but don't go below the minimum.
                        double speedScale = Math.sqrt(distanceToFinalSq) / Math.sqrt(DECELERATION_DISTANCE_SQUARED);
                        currentSpeed = (float) Math.max(MIN_DECELERATION_SPEED, currentSpeed * speedScale);
                    }
                }
                // Apply normal steering towards the distant target with the calculated speed.
                applyNormalMovement(result.target(), currentSpeed);
                break;
        }
    }

    private void tickEngaging() {
        // Check if target is still valid
        if (engageTarget == null || engageTarget.isDead()) {
            finishEngaging(EngageResult.TARGET_GONE);
            return;
        }
        Location agentLocation = persona.getLocation();
        if (agentLocation.distanceSquared(engageTarget.getLocation()) < engageProximitySquared) {
            finishEngaging(EngageResult.SUCCESS);
            return;
        }
        // Stop any jump
        if (persona.getPersonaEntity() != null) {
            persona.getPersonaEntity().getJumpControl().stop();
        }
        // Dampen speed as we get closer for a smoother stop
        Location targetLocation = engageTarget.getLocation();
        double distance = agentLocation.distance(targetLocation);
        float speed = getSpeed();
        if (distance < 2.0) {
            speed *= (distance / 2.0);
        }
        // Use new steerDirectlyTowards helper method
        steerDirectlyTowards(targetLocation, Math.max(speed, 0.1f));
        if (persona.getPersonaEntity() != null) {
            Location eyeLocation = engageTarget.getLocation().add(0, engageTarget.getHeight() * 0.85, 0);
            persona.getPersonaEntity().getLookControl().setLookAt(eyeLocation.getX(), eyeLocation.getY(), eyeLocation.getZ());
        }
    }

    private void tickFollowing() {
        if (followTarget == null || followTarget.isDead()) {
            finishFollowing(NavigationResult.FAILURE); // Target is gone
            return;
        }
        Location agentLocation = persona.getLocation();
        Location targetLocation = followTarget.getLocation();
        double distanceSq = agentLocation.distanceSquared(targetLocation);
        if (distanceSq < stopDistanceSquared) {
            // Comfort Zone: Stop and look.
            persona.getPersonaEntity().getMoveControl().stop();
            persona.getPersonaEntity().getJumpControl().stop();
            persona.getPersonaEntity().getLookControl().setLookAt(targetLocation.getX(),
                    targetLocation.getY() + followTarget.getHeight() * 0.85, targetLocation.getZ());
            currentFollowPath = null; // We are close, no path needed.
            pathFollower = null;
        } else if (distanceSq < followDistanceSquared
                && !NavigationHelper.isObstacleDirectlyInFront(agentLocation, targetLocation)
                && NavigationHelper.hasLineOfSight(agentLocation, targetLocation, agentLocation.getWorld())) {
            // Direct Steering Zone: Move directly towards target.
            // For direct steering, we still need a ground-based target.
            // This is a fast check that prevents running into walls.
            Location steerTarget = followTarget.isOnGround()
                    ? targetLocation
                    : NavigationHelper.findGround(targetLocation, 5).orElse(targetLocation);

            persona.getPersonaEntity().getJumpControl().stop();
            steerDirectlyTowards(steerTarget, getSpeed());
            persona.getPersonaEntity().getLookControl().setLookAt(targetLocation.getX(),
                    targetLocation.getY() + followTarget.getHeight() * 0.85, targetLocation.getZ());
            currentFollowPath = null;
            pathFollower = null;
        } else {
            // Pathfinding Zone: Too far or no line of sight.
            ticksUntilNextRepath--;
            // Target moved from path end
            boolean needsRepath = currentFollowPath == null || pathFollower == null || pathFollower.isFinished(agentLocation)
                    || ticksUntilNextRepath <= 0 ||
                    currentFollowPath.getPoint(currentFollowPath.size() - 1).distanceSquared(targetLocation) > 4.0;
            if (needsRepath) {
                ticksUntilNextRepath = REPATH_INTERVAL_TICKS;
                Location pathTarget = NavigationHelper.findGround(targetLocation, 20).orElse(targetLocation);
                AStarPathfinder.findPath(agentLocation, pathTarget, NavigationParameters.DEFAULT)
                        .thenAcceptAsync(pathOpt -> {
                            if (state != State.FOLLOWING)
                                return; // State changed while pathfinding
                            if (pathOpt.isPresent() && !pathOpt.get().isEmpty()) {
                                currentFollowPath = pathOpt.get();
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
     * This method is called by the main tick loop for any active state.
     * 
     * @return true if the persona is considered stuck, false otherwise.
     */
    private boolean checkIfStuck() {
        PersonaEntity personaEntity = persona.getPersonaEntity();
        if (personaEntity.getMoveControl().getOperation() == PersonaMoveControl.Operation.WAIT) {
            totalTicksStuck = 0; // Reset counter to be safe
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

    public void cancelCurrentOperation(NavigationResult pathingReason, EngageResult engagingReason) {
        switch (state) {
            case PATH_FOLLOWING:
                finishPathing(pathingReason);
                break;
            case ENGAGING:
                finishEngaging(engagingReason);
                break;
            case FOLLOWING:
                finishFollowing(pathingReason);
                break;
            case IDLE:
                // Nothing to do
                break;
        }
    }

    private void finishPathing(NavigationResult result) {
        if (navigationFuture != null && !navigationFuture.isDone()) {
            navigationFuture.complete(result);
        }
        reset();
    }

    public void finishEngaging(EngageResult result) {
        if (engageFuture != null && !engageFuture.isDone()) {
            engageFuture.complete(result);
        }
        reset();
    }

    private void finishFollowing(NavigationResult result) {
        if (navigationFuture != null && !navigationFuture.isDone()) {
            navigationFuture.complete(result);
        }
        reset();
    }

    private void reset() {
        if (persona.isSpawned()) {
            PersonaEntity personaEntity = persona.getPersonaEntity();
            personaEntity.getMoveControl().stop();
            personaEntity.getJumpControl().stop();
            personaEntity.getLookControl().stopLooking();
        }
        // Reset future
        this.navigationFuture = null;
        // Reset path state
        this.currentPath = null;
        this.pathFollower = null;
        // Reset engage state
        this.engageFuture = null;
        this.engageTarget = null;
        // Reset follow state
        this.followTarget = null;
        this.currentFollowPath = null;
        // Reset to idle
        this.state = State.IDLE;
        // Reset jump state
        resetJumpState();
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public Path getCurrentPath() {
        return currentPath;
    }

    /**
     * Manages the multi-tick process of executing a jump.
     */
    private void handleJumpNavigation() {
        PersonaEntity personaEntity = persona.getPersonaEntity();
        Location currentLocation = persona.getLocation();
        Location nextWaypoint = this.jumpTargetWaypoint; // Use the stored target for the jump
        if (nextWaypoint == null) { // Safety check
            resetJumpState();
            return;
        }
        switch (jumpState) {
            case PREPARING:
                jumpStateTicks++;
                // Wait until the entity is on solid ground to ensure a good jump.
                if (personaEntity.onGround()) {
                    // Initiate the jump.
                    personaEntity.getJumpControl().jump();
                    jumpState = JumpState.JUMPING;
                    jumpStateTicks = 0;
                    // Apply slight forward movement to get closer to the ledge.
                    applyJumpMovement(nextWaypoint, 0.5f);
                } else if (jumpStateTicks > 10) { // Timeout if we can't find ground.
                    persona.getManager().getPlugin().getLogger()
                            .warning("Persona " + persona.getName() + " failed jump prep (no ground).");
                    resetJumpState();
                }
                break;
            case JUMPING:
                jumpStateTicks++;
                // Re-trigger jump for a couple of ticks if still on ground to ensure it registers.
                if (jumpStateTicks <= 2 && personaEntity.onGround()) {
                    personaEntity.getJumpControl().jump();
                }
                // Check if we have successfully started ascending.
                if (currentLocation.getY() > jumpStartY + 0.1) {
                    jumpState = JumpState.ASCENDING;
                    jumpStateTicks = 0;
                    applyJumpMovement(nextWaypoint, 1.0f); // Apply full forward momentum now.
                } else if (jumpStateTicks > 5) { // Timeout if we fail to leave the ground.
                    persona.getManager().getPlugin().getLogger()
                            .warning("Persona " + persona.getName() + " failed to start jump ascent.");
                    resetJumpState();
                }
                break;
            case ASCENDING:
                jumpStateTicks++;
                applyJumpMovement(nextWaypoint, 1.0f); // Continue forward momentum.
                // Instead of resetting, transition to DESCENDING at the jump's apex, ensuring we maintain control
                // over movement while falling.
                if (personaEntity.getDeltaMovement().y < 0) {
                    jumpState = JumpState.DESCENDING;
                    jumpStateTicks = 0;
                } else if (jumpStateTicks > 20) { // Timeout to prevent getting stuck mid-air.
                    // CHANGE: Added call to jumpControl.stop() on timeout to clean up state.
                    persona.getPersonaEntity().getJumpControl().stop();
                    persona.getManager().getPlugin().getLogger()
                            .warning("Persona " + persona.getName() + " timed out during jump ascent.");
                    resetJumpState();
                }
                break;
            case DESCENDING:
                jumpStateTicks++;
                applyJumpMovement(nextWaypoint, 1.0f); // Continue forward momentum to clear the gap.
                // The jump is complete once we are back on the ground.
                if (personaEntity.onGround()) {
                    // CHANGE: Added call to jumpControl.stop() on successful landing.
                    persona.getPersonaEntity().getJumpControl().stop();
                    resetJumpState();
                } else if (jumpStateTicks > 30) { // Timeout to prevent getting stuck in a fall loop.
                    // CHANGE: Added call to jumpControl.stop() on timeout.
                    persona.getPersonaEntity().getJumpControl().stop();
                    persona.getManager().getPlugin().getLogger()
                            .warning("Persona " + persona.getName() + " timed out during jump descent.");
                    resetJumpState();
                }
                break;
            case DROPPING:
                jumpStateTicks++;
                if (jumpStateTicks == 1) {
                    // This provides the necessary horizontal momentum to clear any small gap.
                    applyJumpMovement(nextWaypoint, 0.3f);
                } else {
                    // Stop all forward movement.
                    personaEntity.getMoveControl().stop();
                }
                if (personaEntity.onGround()) {
                    resetJumpState();
                } else if (jumpStateTicks > 30) { // Timeout to prevent getting stuck in a fall loop.
                    persona.getManager().getPlugin().getLogger()
                            .warning("Persona " + persona.getName() + " timed out during jump descent.");
                    resetJumpState();
                }
                break;
            case EXITING_WATER:
                jumpStateTicks++;
                // If we've successfully landed, the maneuver is over.
                if (personaEntity.onGround()) {
                    persona.getPersonaEntity().getJumpControl().stop();
                    resetJumpState();
                    return;
                }
                // Timeout for the entire maneuver.
                if (jumpStateTicks > 60) { // 3 seconds
                    // CHANGE: Explicitly stop the jump control on timeout.
                    persona.getPersonaEntity().getJumpControl().stop();
                    persona.getManager().getPlugin().getLogger()
                            .warning("Persona " + persona.getName() + " timed out trying to exit water.");
                    resetJumpState();
                    return;
                }
                // Phase 1: The Lunge. Get horizontally close to the ledge.
                double horizontalDistSq = currentLocation.toVector().setY(0)
                        .distanceSquared(nextWaypoint.toVector().setY(0));
                if (horizontalDistSq > 0.5 * 0.5) {
                    // We are not at the ledge yet, so swim towards it.
                    // We don't want to swim UP, just forward.
                    applyJumpMovement(nextWaypoint, 0.8f); // Use applyJumpMovement for strong forward momentum
                } else {
                    // Phase 2: The Jump. We are at the ledge.
                    // Stop forward movement to prevent jittering against the block.
                    personaEntity.getMoveControl().stop();
                    // Repeatedly trigger the jump control, simulating a player spamming spacebar.
                    // With the refactored JumpControl, this will now provide sustained upward thrust.
                    personaEntity.getJumpControl().jump();
                }
                break;
            case NONE:
                // This case should not be reached, but as a safeguard:
                resetJumpState();
                break;
        }
    }

    /**
     * Resets all jump-related state variables.
     */
    private void resetJumpState() {
        this.jumpState = JumpState.NONE;
        this.jumpStateTicks = 0;
        this.jumpStartY = 0;
        this.jumpTargetWaypoint = null;
    }

    /**
     * Applies forward movement towards a target with a given speed.
     */
    private void applyNormalMovement(Location target, float speed) {
        PersonaEntity personaEntity = persona.getPersonaEntity();
        if (personaEntity != null) {
            // CHANGE: Added a call to jumpControl.stop() to ensure vertical movement is
            // cancelled when transitioning from water/jump to normal walking.
            personaEntity.getJumpControl().stop();
            personaEntity.setShiftKeyDown(false);
        }
        PersonaMoveControl moveControl = persona.getPersonaEntity().getMoveControl();
        moveControl.setWantedPosition(
                target.getX(),
                target.getY(),
                target.getZ(),
                speed);
    }

    private void applySwimMovement(Location target, SteeringResult.VerticalDirection direction) {
        PersonaEntity personaEntity = persona.getPersonaEntity();
        if (personaEntity == null)
            return;
        // Check for water-exit jump context.
        if (direction == SteeringResult.VerticalDirection.UP && !target.getBlock().isLiquid()) {
            this.jumpState = JumpState.EXITING_WATER;
            this.jumpStateTicks = 0;
            this.jumpTargetWaypoint = target;
            return;
        }
        // Apply forward momentum for normal in-water swimming
        PersonaMoveControl moveControl = personaEntity.getMoveControl();
        moveControl.setWantedPosition(target.getX(), target.getY(), target.getZ(), getSpeed() * 0.7f);
        // CHANGE: Replaced direct entity state manipulation with calls to the refactored JumpControl.
        // This unifies the vertical movement mechanism and fixes the swimming bugs.
        switch (direction) {
            case UP:
                // Use the jump control to apply upward thrust.
                personaEntity.getJumpControl().jump();
                personaEntity.setShiftKeyDown(false);
                break;
            case DOWN:
                // Stop any upward thrust and use sneaking to swim down.
                personaEntity.getJumpControl().stop();
                personaEntity.setShiftKeyDown(true);
                break;
            case NONE:
                // Stop any upward thrust for horizontal swimming.
                personaEntity.getJumpControl().stop();
                personaEntity.setShiftKeyDown(false);
                break;
        }
    }

    // Helper method for direct steering
    // Target X and Z, but agent's current Y to prevent flying/digging on uneven terrain
    private void steerDirectlyTowards(Location target, float speed) {
        PersonaMoveControl moveControl = persona.getPersonaEntity().getMoveControl();
        moveControl.setWantedPosition(target.getX(), persona.getLocation().getY(), target.getZ(), speed);
    }

    /**
     * Applies boosted forward movement for clearing gaps during a jump.
     */
    private void applyJumpMovement(Location target, float amount) {
        // Apply a speed boost for better distance coverage during a jump.
        PersonaMoveControl moveControl = persona.getPersonaEntity().getMoveControl();
        moveControl.setWantedPosition(
                target.getX(),
                target.getY(),
                target.getZ(),
                getSpeed() * amount);
    }

    private void log() {
        Location currentPos = persona.getLocation();
        StringBuilder sb = new StringBuilder();
        sb.append("Persona ").append(persona.getName()).append(" is stuck! Failing current task.\n");
        sb.append(String.format("  - Current Location: (%s, %.2f, %.2f, %.2f)\n",
                currentPos.getWorld().getName(), currentPos.getX(), currentPos.getY(), currentPos.getZ()));
        if (state == State.PATH_FOLLOWING && currentPath != null && pathFollower != null && !currentPath.isEmpty()) {
            sb.append("  - Following Path (").append(currentPath.size()).append(" points total):\n");
            int currentIndex = pathFollower.getCurrentIndex();
            // Show a few points before and after the current target for context.
            int start = Math.max(0, currentIndex - 2);
            int end = Math.min(currentPath.size(), currentIndex + 5);
            for (int i = start; i < end; i++) {
                Location point = currentPath.getPoint(i);
                String prefix = (i == currentIndex) ? " -> " : "    ";
                sb.append(prefix).append(String.format("[%d] (%.1f, %.1f, %.1f)\n", i, point.getX(), point.getY(), point.getZ()));
            }
        } else if (state == State.ENGAGING && engageTarget != null) {
            Location targetPos = engageTarget.getLocation();
            sb.append("  - Engaging Target: ").append(engageTarget.getType())
                    .append(String.format(" at (%.2f, %.2f, %.2f)\n", targetPos.getX(), targetPos.getY(), targetPos.getZ()));
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