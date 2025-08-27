package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.MetadataKeys;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.EngageResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.movement.PersonaMoveControl;
import com.ionsignal.minecraft.ionnerrus.util.DebugPath;

import net.minecraft.world.entity.ai.control.MoveControl;

import org.bukkit.Location;
import org.bukkit.entity.Item;

import java.util.concurrent.CompletableFuture;

public class Navigator {
    // Movement constants
    private static final float DEFAULT_MOVEMENT_SPEED = 1.0f;
    private static final float JUMP_MOVEMENT_BOOST = 1.0f;

    // Stuck check constants
    private static final int STUCK_TIME_THRESHOLD_TICKS = 40;
    private static final int STUCK_CHECK_INTERVAL_TICKS = 10;
    private static final double STUCK_DISTANCE_THRESHOLD_SQUARED = 0.25; // 0.1 * 0.1 blocks

    // Deceleration constants
    private static final double DECELERATION_DISTANCE_SQUARED = 1.5 * 1.5; // Start slowing down 2 blocks away
    private static final float MIN_DECELERATION_SPEED = 0.2f; // Don't slow down to a crawl

    private final Persona persona;

    private State state = State.IDLE;

    private enum State {
        IDLE, PATH_FOLLOWING, ENGAGING
    }

    // Path Following State
    private CompletableFuture<NavigationResult> navigationFuture;
    private Path currentPath;
    private PathFollower pathFollower;

    // Engaging State
    private Item engageTarget;
    private CompletableFuture<EngageResult> engageFuture;
    private Location stuckCheckLocation;
    private int ticksSinceStuckCheck;
    private int totalTicksStuck;

    private enum JumpState {
        NONE, PREPARING, JUMPING, ASCENDING, DESCENDING, DROPPING
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

    public CompletableFuture<EngageResult> engageOn(Item target) {
        if (isBusy()) {
            cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
        }
        resetStuckDetection();
        state = State.ENGAGING;
        engageTarget = target;
        engageFuture = new CompletableFuture<>();
        return engageFuture;
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
        }
    }

    private void tickPathFollowing() {
        if (pathFollower == null) {
            return;
        }
        Location currentLocation = persona.getLocation();
        if (pathFollower.isFinished(currentLocation)) {
            finishPathing(NavigationResult.SUCCESS);
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
            case WALK:
                // Apply normal steering towards the distant target.
                float currentSpeed = getSpeed();
                Location finalDestination = currentPath.getPoint(currentPath.size() - 1);
                double distanceToFinalSq = currentLocation.distanceSquared(finalDestination);
                if (distanceToFinalSq < DECELERATION_DISTANCE_SQUARED) {
                    // Apply the scaling to the base speed, but don't go below the minimum.
                    double speedScale = Math.sqrt(distanceToFinalSq) / Math.sqrt(DECELERATION_DISTANCE_SQUARED);
                    currentSpeed = (float) Math.max(MIN_DECELERATION_SPEED, currentSpeed * speedScale);
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
        // Movement Logic
        Location itemLocation = engageTarget.getLocation();
        Location agentLocation = persona.getLocation();
        // Target X and Z of the item, but the agent's current Y to prevent flying/digging
        Location moveTarget = new Location(itemLocation.getWorld(), itemLocation.getX(), agentLocation.getY(), itemLocation.getZ());
        // Dampen speed as we get closer for a smoother stop
        double distance = agentLocation.distance(itemLocation);
        float speed = getSpeed();
        if (distance < 2.0) {
            speed *= (distance / 2.0);
        }
        MoveControl moveControl = persona.getPersonaEntity().getMoveControl();
        moveControl.setWantedPosition(moveTarget.getX(), moveTarget.getY(), moveTarget.getZ(), Math.max(speed, 0.1f));
    }

    /**
     * Checks if the persona is stuck by monitoring its movement over time.
     * This method is called by the main tick loop for any active state.
     * 
     * @return true if the persona is considered stuck, false otherwise.
     */
    private boolean checkIfStuck() {
        ticksSinceStuckCheck++;
        if (ticksSinceStuckCheck >= STUCK_CHECK_INTERVAL_TICKS) {
            // CHANGE: Replaced the 3D distance check with a 2D (XZ plane) check.
            // This prevents vertical movement (like a jump loop) from being counted as progress.
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

    private void reset() {
        if (persona.isSpawned()) {
            PersonaEntity personaEntity = persona.getPersonaEntity();
            if (personaEntity.getMoveControl() instanceof PersonaMoveControl personaMoveControl) {
                personaMoveControl.stop();
            }
        }
        this.navigationFuture = null;
        this.currentPath = null;
        this.pathFollower = null;
        this.engageFuture = null;
        this.engageTarget = null;
        this.state = State.IDLE;
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
                    resetJumpState();
                } else if (jumpStateTicks > 30) { // Timeout to prevent getting stuck in a fall loop.
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
                    if (personaEntity.getMoveControl() instanceof PersonaMoveControl personaMoveControl) {
                        personaMoveControl.stop();
                    }
                }
                if (personaEntity.onGround()) {
                    resetJumpState();
                } else if (jumpStateTicks > 30) { // Timeout to prevent getting stuck in a fall loop.
                    persona.getManager().getPlugin().getLogger()
                            .warning("Persona " + persona.getName() + " timed out during jump descent.");
                    resetJumpState();
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
        MoveControl moveControl = persona.getPersonaEntity().getMoveControl();
        moveControl.setWantedPosition(
                target.getX(),
                target.getY(),
                target.getZ(),
                speed);
    }

    /**
     * Applies boosted forward movement for clearing gaps during a jump.
     */
    private void applyJumpMovement(Location target, float amount) {
        // Apply a speed boost for better distance coverage during a jump.
        MoveControl moveControl = persona.getPersonaEntity().getMoveControl();
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
            sb.append("  - Engaging Target: ").append(engageTarget.getItemStack().getType())
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