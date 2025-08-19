package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.MetadataKeys;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.movement.PersonaMoveControl;

import net.minecraft.world.entity.ai.control.MoveControl;

import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;

public class Navigator {
    private static final float DEFAULT_MOVEMENT_SPEED = 1.0f;
    private final Persona persona;

    private CompletableFuture<NavigationResult> navigationFuture;
    private Path currentPath;
    private PathFollower pathFollower;

    private enum JumpState {
        NONE, PREPARING, JUMPING, ASCENDING
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
        if (isNavigating()) {
            cancelNavigation(NavigationResult.CANCELLED);
        }
        this.navigationFuture = new CompletableFuture<>();
        AStarPathfinder.findPath(persona.getLocation(), target, params).thenAcceptAsync(pathOptional -> {
            if (pathOptional.isPresent() && !pathOptional.get().isEmpty()) { // CHANGE: Check isEmpty() instead of waypoints
                this.currentPath = pathOptional.get();
                this.pathFollower = new PathFollower(this.currentPath);
            } else {
                finish(NavigationResult.UNREACHABLE);
            }
        }, persona.getManager().getPlugin().getMainThreadExecutor());
        return this.navigationFuture;
    }

    public CompletableFuture<NavigationResult> navigateTo(Path path) {
        if (isNavigating()) {
            cancelNavigation(NavigationResult.CANCELLED);
        }
        this.navigationFuture = new CompletableFuture<>();
        // Directly use the provided path, skipping the pathfinding step.
        if (path != null && !path.isEmpty()) {
            // This needs to run on the main thread to safely set the path follower.
            persona.getManager().getPlugin().getMainThreadExecutor().execute(() -> {
                this.currentPath = path;
                this.pathFollower = new PathFollower(this.currentPath);
            });
        } else {
            // Complete immediately if path is invalid
            finish(NavigationResult.UNREACHABLE);
        }
        return this.navigationFuture;
    }

    public void tick() {
        if (!isNavigating() || pathFollower == null || !persona.isSpawned()) {
            return;
        }
        if (persona.isInventoryLocked()) {
            cancelNavigation(NavigationResult.CANCELLED);
            return;
        }
        Location currentLocation = persona.getLocation();
        if (pathFollower.isFinished(currentLocation)) {
            finish(NavigationResult.SUCCESS);
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
            case WALK:
                // Apply normal steering towards the distant target.
                applyNormalMovement(result.target());
                break;
        }
    }

    public void cancelNavigation(NavigationResult reason) {
        finish(reason);
    }

    private void finish(NavigationResult result) {
        PersonaEntity personaEntity = persona.getPersonaEntity();
        if (personaEntity.getMoveControl() instanceof PersonaMoveControl personaMoveControl) {
            personaMoveControl.stop();
        }
        if (navigationFuture != null && !navigationFuture.isDone()) {
            navigationFuture.complete(result);
        }
        this.currentPath = null;
        this.pathFollower = null;
        resetJumpState();
    }

    public boolean isNavigating() {
        return navigationFuture != null && !navigationFuture.isDone();
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
                    MoveControl moveControl = personaEntity.getMoveControl();
                    moveControl.setWantedPosition(
                            nextWaypoint.getX(),
                            nextWaypoint.getY(),
                            nextWaypoint.getZ(),
                            getSpeed() * 0.5f // Reduced speed for better jump control.
                    );
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
                    applyJumpMovement(nextWaypoint); // Apply full forward momentum now.
                } else if (jumpStateTicks > 5) { // Timeout if we fail to leave the ground.
                    persona.getManager().getPlugin().getLogger()
                            .warning("Persona " + persona.getName() + " failed to start jump ascent.");
                    resetJumpState();
                }
                break;
            case ASCENDING:
                jumpStateTicks++;
                applyJumpMovement(nextWaypoint); // Continue forward momentum.
                // Check if the jump is complete (reached target height or started falling).
                // The deltaMovement check detects the apex of the jump.
                if (currentLocation.getY() >= nextWaypoint.getY() - 0.5 || personaEntity.getDeltaMovement().y < 0) {
                    resetJumpState();
                } else if (jumpStateTicks > 20) { // Timeout to prevent getting stuck mid-air.
                    persona.getManager().getPlugin().getLogger()
                            .warning("Persona " + persona.getName() + " timed out during jump ascent.");
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
     * Applies standard forward movement towards a target.
     */
    private void applyNormalMovement(Location target) {
        MoveControl moveControl = persona.getPersonaEntity().getMoveControl();
        moveControl.setWantedPosition(
                target.getX(),
                target.getY(),
                target.getZ(),
                getSpeed());
    }

    /**
     * Applies boosted forward movement for clearing gaps during a jump.
     */
    private void applyJumpMovement(Location target) {
        MoveControl moveControl = persona.getPersonaEntity().getMoveControl();
        // Apply a speed boost for better distance coverage during a jump.
        moveControl.setWantedPosition(
                target.getX(),
                target.getY(),
                target.getZ(),
                getSpeed() * 1.2f);
    }

    /**
     * Retrieves the persona's configured movement speed.
     */
    private float getSpeed() {
        return persona.getMetadata().get(MetadataKeys.MOVEMENT_SPEED, Double.class)
                .orElse((double) DEFAULT_MOVEMENT_SPEED).floatValue();
    }
}