package com.ionsignal.minecraft.ionnerrus.persona.components.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken.Registration;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionController;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus;
import com.ionsignal.minecraft.ionnerrus.persona.action.impl.BlockBreakerAction;
import com.ionsignal.minecraft.ionnerrus.persona.action.impl.PlaceBlockAction;
import com.ionsignal.minecraft.ionnerrus.persona.animation.PlayerAnimation;
import com.ionsignal.minecraft.ionnerrus.persona.components.*;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.ActionResult;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.LookResult;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.LocomotionController;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Navigator;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.persona.orientation.OrientationController;
import com.ionsignal.minecraft.ionnerrus.persona.orientation.OrientationIntent;

import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import com.google.common.base.Preconditions;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class BukkitPhysicalBody implements PhysicalBody {
    private final PersonaEntity personaEntity;

    // High-level systems
    private final Navigator navigator;
    private final ActionController actionController;
    private final OrientationController orientationController;
    private final LocomotionController locomotionController;
    private final PersonaLookControl lookControl;

    // Active Operation Futures (The Bridge's Source of Truth)
    private volatile CompletableFuture<MovementResult> activeMovement;
    private volatile CompletableFuture<ActionResult> activeAction;

    // Internal state for tracking Navigator completion
    private MovementResult pendingNavigationResult;

    // State for inventory interaction
    private Entity interactionTarget;

    // Cached Capability Instances (Optimization: Allocation per instance, not per call)
    private final MovementCapability movementCapability;
    private final OrientationCapability orientationCapability;
    private final ActionCapability actionCapability;
    private final StateCapability stateCapability;

    public BukkitPhysicalBody(Persona persona, PersonaEntity personaEntity) {
        this.personaEntity = personaEntity;
        this.locomotionController = new LocomotionController(personaEntity);
        this.navigator = new Navigator(persona, this.locomotionController);
        this.actionController = new ActionController(personaEntity);
        this.orientationController = new OrientationController(personaEntity);
        this.lookControl = personaEntity.getLookControl();
        // Initialize Capabilities
        this.movementCapability = createMovementCapability();
        this.orientationCapability = createOrientationCapability();
        this.actionCapability = createActionCapability();
        this.stateCapability = createStateCapability();
    }

    @Override
    public void tick() {
        // High-priority override for inventory interaction
        if (interactionTarget != null) {
            if (!interactionTarget.isValid()) {
                interactionTarget = null; // Target lost, resume normal operation
            } else {
                // 1. Force Stop Movement
                personaEntity.getMoveControl().stop();
                personaEntity.getJumpControl().stop();
                // 2. Force Look at Target
                lookControl.setLookAt(new Vec3(interactionTarget.getLocation().getX(),
                        interactionTarget.getLocation().getY() + interactionTarget.getHeight() * 0.85,
                        interactionTarget.getLocation().getZ()));
                lookControl.setBodyMode(PersonaLookControl.BodyMode.LOCKED);
                lookControl.tick();
                // 3. Skip other logic (Navigator, Actions) to "pause" them
                return;
            }
        }
        tickMovement();
        tickOrientation();
        tickActions();
    }

    @Override
    public void onInventoryOpen(HumanEntity player) {
        this.interactionTarget = player;
    }

    @Override
    public void onInventoryClose() {
        this.interactionTarget = null;
    }

    private void tickMovement() {
        navigator.tick();
        locomotionController.tick();
        // Check if Navigator finished this tick
        if (pendingNavigationResult != null) {
            CompletableFuture<MovementResult> currentFuture = activeMovement;
            MovementResult resultToComplete = pendingNavigationResult;
            activeMovement = null;
            pendingNavigationResult = null;
            if (currentFuture != null && !currentFuture.isDone()) {
                currentFuture.complete(resultToComplete);
            }
            pendingNavigationResult = null;
        }
    }

    private void tickOrientation() {
        // Arbitrate between Physics and Cognition
        Optional<OrientationIntent> physicalNecessity = locomotionController.getOrientationIntent();
        OrientationIntent cognitiveDesire = orientationController.getCurrentSkillIntent();
        OrientationIntent finalIntent;
        if (physicalNecessity.isPresent() && !(physicalNecessity.get() instanceof OrientationIntent.Idle)) {
            // Physics overrides Cognition (e.g., Falling overrides looking at a friend)
            finalIntent = physicalNecessity.get();
            // Notify controller it is being overridden so it doesn't falsely timeout the skill
            orientationController.notifyOverride();
        } else {
            // Default to cognitive desire
            finalIntent = cognitiveDesire;
        }
        orientationController.actuate(finalIntent);
    }

    private void tickActions() {
        actionController.tick();
        if (activeAction != null && !activeAction.isDone()) {
            Optional<ActionStatus> statusOpt = actionController.getLastCompletedStatus();
            if (statusOpt.isPresent()) {
                ActionStatus status = statusOpt.get();
                if (status != ActionStatus.RUNNING) {
                    ActionResult result = switch (status) {
                        case SUCCESS -> ActionResult.SUCCESS;
                        case FAILURE -> ActionResult.FAILURE;
                        case CANCELLED -> ActionResult.CANCELLED;
                        default -> ActionResult.FAILURE;
                    };
                    CompletableFuture<ActionResult> future = activeAction;
                    activeAction = null; // Clear state FIRST to allow chaining
                    if (future != null && !future.isDone()) {
                        future.complete(result);
                    }
                }
            }
        }
    }

    @Override
    public MovementCapability movement() {
        return this.movementCapability;
    }

    @Override
    public OrientationCapability orientation() {
        return this.orientationCapability;
    }

    @Override
    public ActionCapability actions() {
        return this.actionCapability;
    }

    @Override
    public StateCapability state() {
        return this.stateCapability;
    }

    @Override
    public Entity getBukkitEntity() {
        return personaEntity.getBukkitEntity();
    }

    /**
     * Helper to bind a future to an execution token's lifecycle.
     * If the token is cancelled, the onCancel runnable is executed on the main thread,
     * and the future is completed with the cancelledValue.
     */
    private <T> void bindToken(CompletableFuture<T> future, ExecutionToken token, T cancelledValue, Runnable onCancel) {
        Registration reg = token.onCancel(() -> {
            Runnable cancellationLogic = () -> {
                onCancel.run();
                if (!future.isDone()) {
                    future.complete(cancelledValue);
                }
            };
            // Execute immediately if allowed, otherwise schedule
            if (Bukkit.isPrimaryThread()) {
                cancellationLogic.run();
            } else {
                CompletableFuture.runAsync(cancellationLogic, IonNerrus.getInstance().getMainThreadExecutor());
            }
        });
        // Close registration when future completes to prevent leaks
        future.whenComplete((res, ex) -> reg.close());
    }

    private MovementCapability createMovementCapability() {
        return new MovementCapability() {
            @Override
            public CompletableFuture<MovementResult> moveTo(Location target, ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                validateMovement();
                CompletableFuture<MovementResult> future = new CompletableFuture<>();
                activeMovement = future;
                pendingNavigationResult = null;
                bindToken(future, token, MovementResult.CANCELLED, () -> this.stop());
                navigator.navigateTo(target, token).whenComplete((result, ex) -> {
                    pendingNavigationResult = (ex != null) ? MovementResult.FAILURE : result;
                });
                return future;
            }

            @Override
            public CompletableFuture<MovementResult> moveTo(Path path, ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                validateMovement();
                CompletableFuture<MovementResult> future = new CompletableFuture<>();
                activeMovement = future;
                pendingNavigationResult = null;
                bindToken(future, token, MovementResult.CANCELLED, () -> this.stop());
                navigator.navigateTo(path, token).whenComplete((result, ex) -> {
                    pendingNavigationResult = (ex != null) ? MovementResult.FAILURE : result;
                });
                return future;
            }

            @Override
            public CompletableFuture<MovementResult> engage(Entity target, double stopDistanceSquared, ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                validateMovement();
                CompletableFuture<MovementResult> future = new CompletableFuture<>();
                activeMovement = future;
                pendingNavigationResult = null;
                bindToken(future, token, MovementResult.CANCELLED, () -> this.stop());
                navigator.engageOn(target, stopDistanceSquared, token).whenComplete((result, ex) -> {
                    pendingNavigationResult = (ex != null) ? MovementResult.STUCK : result;
                });
                return future;
            }

            @Override
            public CompletableFuture<MovementResult> follow(Entity target, double followDistance, double stopDistance,
                    ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                validateMovement();
                CompletableFuture<MovementResult> future = new CompletableFuture<>();
                activeMovement = future;
                pendingNavigationResult = null;
                bindToken(future, token, MovementResult.CANCELLED, () -> this.stop());
                navigator.followOn(target, followDistance, stopDistance, token).whenComplete((result, ex) -> {
                    pendingNavigationResult = (ex != null) ? MovementResult.FAILURE : result;
                });
                return future;
            }

            private void validateMovement() {
                if (activeMovement != null && !activeMovement.isDone()) {
                    throw new IllegalStateException("Cannot start movement: already moving");
                }
            }

            @Override
            public void stop() {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                // Navigator cancel call needs a result, but since we are stopping, the future will be completed by
                // the navigator's completion handler or we can cancel it explicitly.
                CompletableFuture<MovementResult> currentFuture = activeMovement;
                // Clear global state
                activeMovement = null;
                // Stop physics
                navigator.cancelCurrentOperation(MovementResult.CANCELLED);
                // Complete future if not already done (e.g. by tick loop)
                if (currentFuture != null && !currentFuture.isDone()) {
                    currentFuture.complete(MovementResult.CANCELLED);
                }
            }

            @Override
            public boolean isMoving() {
                return activeMovement != null && !activeMovement.isDone();
            }
        };
    }

    private OrientationCapability createOrientationCapability() {
        return new OrientationCapability() {
            @Override
            public CompletableFuture<LookResult> lookAt(Location target, boolean turnBody, ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                return orientationController.lookAt(target, turnBody, token);
            }

            @Override
            public void face(Location target, boolean turnBody, ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                orientationController.face(target, turnBody, token);
            }

            @Override
            public void face(Entity target, ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                orientationController.face(target, token);
            }

            @Override
            public void clearLookTarget() {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                orientationController.clear();
            }
        };
    }

    private ActionCapability createActionCapability() {
        return new ActionCapability() {
            @Override
            public CompletableFuture<ActionResult> breakBlock(Block target, ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                if (isBusy()) {
                    throw new IllegalStateException("Cannot break block: Action in progress");
                }
                activeAction = new CompletableFuture<>();
                bindToken(activeAction, token, ActionResult.CANCELLED, () -> this.cancelAction());
                actionController.schedule(new BlockBreakerAction(target));
                return activeAction;
            }

            @Override
            public CompletableFuture<ActionResult> placeBlock(Material material, Location target, ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                if (isBusy()) {
                    throw new IllegalStateException("Cannot place block: Action in progress");
                }
                activeAction = new CompletableFuture<>();
                bindToken(activeAction, token, ActionResult.CANCELLED, () -> this.cancelAction());
                actionController.schedule(new PlaceBlockAction(material, target));
                return activeAction;
            }

            @Override
            @SuppressWarnings("null")
            public void playAnimation(PlayerAnimation animation) {
                int animationId = switch (animation) {
                    case SWING_MAIN_ARM -> ClientboundAnimatePacket.SWING_MAIN_HAND;
                    case TAKE_DAMAGE -> 1;
                    case LEAVE_BED -> ClientboundAnimatePacket.WAKE_UP;
                    case SWING_OFF_HAND -> ClientboundAnimatePacket.SWING_OFF_HAND;
                    case CRITICAL_EFFECT -> ClientboundAnimatePacket.CRITICAL_HIT;
                    case MAGIC_CRITICAL_EFFECT -> ClientboundAnimatePacket.MAGIC_CRITICAL_HIT;
                };
                broadcastPacket(new ClientboundAnimatePacket(personaEntity, animationId));
            }

            @Override
            public CompletableFuture<ActionResult> swapItems(int sourceSlot, int destinationSlot, ExecutionToken token) {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                if (isBusy()) {
                    throw new IllegalStateException("Cannot swap items: Action in progress");
                }
                activeAction = new CompletableFuture<>();
                bindToken(activeAction, token, ActionResult.CANCELLED, () -> this.cancelAction());
                actionController.schedule(
                        new com.ionsignal.minecraft.ionnerrus.persona.action.impl.SwapItemAction(sourceSlot, destinationSlot));
                return activeAction;
            }

            @Override
            public void cancelAction() {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                actionController.clear();
                if (activeAction != null && !activeAction.isDone()) {
                    activeAction.complete(ActionResult.CANCELLED);
                }
                activeAction = null;
            }

            @Override
            public boolean isBusy() {
                return activeAction != null && !activeAction.isDone();
            }

            @SuppressWarnings("null")
            private void broadcastPacket(net.minecraft.network.protocol.Packet<?> packet) {
                ServerLevel level = personaEntity.level();
                var trackedEntity = level.getChunkSource().chunkMap.entityMap.get(personaEntity.getId());
                if (trackedEntity != null) {
                    trackedEntity.broadcast(packet);
                }
            }
        };
    }

    private StateCapability createStateCapability() {
        return new StateCapability() {
            @Override
            public Location getLocation() {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                return personaEntity.getBukkitEntity().getLocation();
            }

            @Override
            public @Nullable PlayerInventory getInventory() {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                return personaEntity.getBukkitEntity().getInventory();
            }

            @Override
            public boolean isAlive() {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                return personaEntity.isAlive() && personaEntity.valid;
            }

            @Override
            public boolean isInventoryOpen() {
                return interactionTarget != null;
            }

            @Override
            public double getBlockReach() {
                Preconditions.checkState(Bukkit.isPrimaryThread(), "Async physical access detected!");
                AttributeInstance attribute = personaEntity.getBukkitEntity()
                        .getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
                return attribute != null ? attribute.getValue() : 4.5;
            }
        };
    }
}