package com.ionsignal.minecraft.ionnerrus.persona.components.impl;

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

import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class BukkitPhysicalBody implements PhysicalBody {
    @SuppressWarnings("unused")
    private final Persona persona;
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

    // Locks for synchronization
    private final Object movementLock = new Object();
    private final Object orientationLock = new Object();
    private final Object actionLock = new Object();

    // State for inventory interaction
    private Entity interactionTarget;

    // Cached Capability Instances (Optimization: Allocation per instance, not per call)
    private final MovementCapability movementCapability;
    private final OrientationCapability orientationCapability;
    private final ActionCapability actionCapability;
    private final StateCapability stateCapability;

    public BukkitPhysicalBody(Persona persona, PersonaEntity personaEntity) {
        this.persona = persona;
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
        // Variables to hold state we extract from the lock
        CompletableFuture<MovementResult> futureToComplete = null;
        MovementResult resultToComplete = null;
        synchronized (movementLock) {
            // 1. Run the Logic (Protected)
            navigator.tick();
            locomotionController.tick();
            // 2. Check for completion (Atomic State Extraction)
            // We read AND clear the state in the same atomic transaction.
            if (pendingNavigationResult != null) {
                futureToComplete = activeMovement;
                resultToComplete = pendingNavigationResult;
                // Cleanup internal state immediately
                activeMovement = null;
                pendingNavigationResult = null;
            }
        }
        // 3. Notify Listeners (Outside the lock)
        // We are now safe to run arbitrary code (callbacks) without holding the lock.
        if (futureToComplete != null && !futureToComplete.isDone()) {
            futureToComplete.complete(resultToComplete);
        }
    }

    private void tickOrientation() {
        // Delegate to controller under lock
        synchronized (orientationLock) {
            // Check if Locomotion (Maneuvers) demands a specific orientation (e.g. jump landing spot)
            Optional<Location> override = locomotionController.getOrientationOverride();
            if (override.isPresent()) {
                // Priority 1: Physics/Maneuver Override
                orientationController.tickOverride(override.get(), locomotionController.shouldLockBodyRotation());
            } else {
                // Priority 2: High-Level Logic (Tracking/Looking)
                orientationController.tick(movement().isMoving());
            }
        }
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
                    completeAction(result);
                }
            }
        }
    }

    private void completeAction(ActionResult result) {
        CompletableFuture<ActionResult> future;
        synchronized (actionLock) {
            future = activeAction;
            activeAction = null; // Clear state FIRST to allow chaining
        }
        if (future != null && !future.isDone()) {
            future.complete(result);
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

    private MovementCapability createMovementCapability() {
        return new MovementCapability() {
            @Override
            public CompletableFuture<MovementResult> moveTo(Location target) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.moveTo(target) must be called on the main server thread.");
                }
                synchronized (movementLock) {
                    validateMovement();
                    CompletableFuture<MovementResult> future = new CompletableFuture<>();
                    activeMovement = future;
                    pendingNavigationResult = null;
                    navigator.navigateTo(target).whenComplete((result, ex) -> {
                        pendingNavigationResult = (ex != null) ? MovementResult.FAILURE : result;
                    });
                    return future;
                }
            }

            @Override
            public CompletableFuture<MovementResult> moveTo(Path path) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.moveTo(path) must be called on the main server thread.");
                }
                synchronized (movementLock) {
                    validateMovement();
                    CompletableFuture<MovementResult> future = new CompletableFuture<>();
                    activeMovement = future;
                    pendingNavigationResult = null;
                    navigator.navigateTo(path).whenComplete((result, ex) -> {
                        pendingNavigationResult = (ex != null) ? MovementResult.FAILURE : result;
                    });
                    return future;
                }
            }

            @Override
            public CompletableFuture<MovementResult> engage(Entity target, double stopDistanceSquared) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.engage() must be called on the main server thread.");
                }
                synchronized (movementLock) {
                    validateMovement();
                    CompletableFuture<MovementResult> future = new CompletableFuture<>();
                    activeMovement = future;
                    pendingNavigationResult = null;
                    navigator.engageOn(target, stopDistanceSquared).whenComplete((result, ex) -> {
                        pendingNavigationResult = (ex != null) ? MovementResult.STUCK : result;
                    });
                    return future;
                }
            }

            @Override
            public CompletableFuture<MovementResult> follow(Entity target, double followDistance, double stopDistance) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.follow() must be called on the main server thread.");
                }
                synchronized (movementLock) {
                    validateMovement();
                    CompletableFuture<MovementResult> future = new CompletableFuture<>();
                    activeMovement = future;
                    pendingNavigationResult = null;
                    navigator.followOn(target, followDistance, stopDistance).whenComplete((result, ex) -> {
                        pendingNavigationResult = (ex != null) ? MovementResult.FAILURE : result;
                    });
                    return future;
                }
            }

            private void validateMovement() {
                if (activeMovement != null && !activeMovement.isDone()) {
                    throw new IllegalStateException("Cannot start movement: already moving");
                }
            }

            @Override
            public void stop() {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.stop() must be called on the main server thread.");
                }
                synchronized (movementLock) {
                    // Navigator cancel call needs a result, but since we are stopping,
                    // the future will be completed by the navigator's completion handler
                    // or we can cancel it explicitly.
                    navigator.cancelCurrentOperation(MovementResult.CANCELLED);
                    if (activeMovement != null && !activeMovement.isDone()) {
                        activeMovement.cancel(true);
                    }
                    activeMovement = null;
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
            public CompletableFuture<LookResult> lookAt(Location target, boolean turnBody) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.lookAt(target, turnBody) must be called on the main server thread.");
                }
                synchronized (orientationLock) {
                    return orientationController.lookAt(target, turnBody);
                }
            }

            @Override
            public void face(Location target, boolean turnBody) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.face(target, turnBody) must be called on the main server thread.");
                }
                synchronized (orientationLock) {
                    orientationController.face(target, turnBody);
                }
            }

            @Override
            public void face(Entity target) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.face(entity) must be called on the main server thread.");
                }
                synchronized (orientationLock) {
                    orientationController.face(target);
                }
            }

            @Override
            public void clearLookTarget() {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.clearLookTarget() must be called on the main server thread.");
                }
                synchronized (orientationLock) {
                    orientationController.clear();
                }
            }
        };
    }

    private ActionCapability createActionCapability() {
        return new ActionCapability() {
            @Override
            public CompletableFuture<ActionResult> breakBlock(Block target) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.breakBlock(target) must be called on the main server thread.");
                }
                synchronized (actionLock) {
                    if (isBusy()) {
                        throw new IllegalStateException("Cannot break block: Action in progress");
                    }
                    activeAction = new CompletableFuture<>();
                    actionController.schedule(new BlockBreakerAction(target));
                    return activeAction;
                }
            }

            @Override
            public CompletableFuture<ActionResult> placeBlock(Material material, Location target) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.placeBlock(material, target) must be called on the main server thread.");
                }
                synchronized (actionLock) {
                    if (isBusy()) {
                        throw new IllegalStateException("Cannot place block: Action in progress");
                    }
                    activeAction = new CompletableFuture<>();
                    actionController.schedule(new PlaceBlockAction(material, target));
                    return activeAction;
                }
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
            public CompletableFuture<ActionResult> swapItems(int sourceSlot, int destinationSlot) {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.swapItems() must be called on the main server thread.");
                }
                synchronized (actionLock) {
                    if (isBusy()) {
                        throw new IllegalStateException("Cannot swap items: Action in progress");
                    }
                    activeAction = new CompletableFuture<>();
                    actionController.schedule(
                            new com.ionsignal.minecraft.ionnerrus.persona.action.impl.SwapItemAction(sourceSlot, destinationSlot));
                    return activeAction;
                }
            }

            @Override
            public void cancelAction() {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.cancelAction() must be called on the main server thread.");
                }
                synchronized (actionLock) {
                    actionController.clear();
                    if (activeAction != null && !activeAction.isDone()) {
                        activeAction.complete(ActionResult.CANCELLED);
                    }
                    activeAction = null;
                }
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
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.getLocation() must be called on the main server thread.");
                }
                return personaEntity.getBukkitEntity().getLocation();
            }

            @Override
            public @Nullable PlayerInventory getInventory() {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.getInventory() must be called on the main server thread.");
                }
                return personaEntity.getBukkitEntity().getInventory();
            }

            @Override
            public boolean isAlive() {
                if (!Bukkit.isPrimaryThread()) {
                    throw new IllegalStateException("PhysicalBody.isAlive() must be called on the main server thread.");
                }
                return personaEntity.isAlive() && personaEntity.valid;
            }

            @Override
            public boolean isInventoryOpen() {
                return interactionTarget != null;
            }
        };
    }
}