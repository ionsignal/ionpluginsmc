package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.gathering;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common.EngageEntitySkill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common.EngageLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common.EquipBestToolResult;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common.EquipBestToolSkill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common.NavigateToLocationResult;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common.NavigateToLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Task;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.gathering.GatherBlockGoal.GatherAttemptResult;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.gathering.GatherBlockGoal.GatherResult;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken.Registration;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.system.SystemError;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * A reactive, state-machine based task for gathering blocks that supports fast/slow collection.
 */
public class GatherBlockTask implements Task {
    private static final boolean DEBUG_VISUALIZATION = true;
    private static final int MAX_REPOSITION_ATTEMPTS = 1;
    private static final int MAX_OBSTRUCTION_DEPTH = 3;
    private static final int MAX_NAVIGATION_RETRIES = 3;
    private static final int MAX_COLLECTION_RETRIES = 2;
    private static final double FAST_MODE_DIST = 3.0;
    private static final double FAST_MODE_DIST_SQUARED = FAST_MODE_DIST * FAST_MODE_DIST;

    // Collection State
    private final Set<Integer> failedFastModeTargets = new HashSet<>();
    private final Queue<Item> pendingDrops = new LinkedList<>();
    private final Set<UUID> collectedItems = new HashSet<>();

    private final Logger logger;
    private final int searchRadius;
    private final Set<Material> targetBlocks;
    private final Set<Location> attemptedLocations;

    // Task State
    private CompletableFuture<Void> taskFuture;
    private State state = State.IDLE;
    private Registration cancelRegistration;
    private Listener pickupListener;

    // Execution Context
    private int waitTicks = 0;
    private int obstructionDepth = 0;
    private int repositionAttempts = 0;

    // Added retry counters
    private int navigationRetries = 0;
    private int collectionRetries = 0;

    private CollectableBlock mainTarget;
    private Location currentBreakTarget;
    private Item currentDropTarget;

    private boolean hadDrops = false;
    private boolean collectedAny = false;

    private enum State {
        IDLE, SCANNING, APPROACHING, EQUIPPING, BREAKING, COLLECTING, FINISHED
    }

    // Added: Internal record for handling vantage point results safely
    // Changed: Now holds an Optional<Path> instead of List<Location>
    private record VantagePointResult(Optional<Path> path) {
    }

    public GatherBlockTask(Set<Material> targetBlocks, int searchRadius, Set<Location> attemptedLocations) {
        this.targetBlocks = targetBlocks;
        this.searchRadius = searchRadius;
        this.attemptedLocations = attemptedLocations;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public CompletableFuture<Void> execute(NerrusAgent agent, ExecutionToken token) {
        this.taskFuture = new CompletableFuture<>();
        this.state = State.SCANNING;
        // Register event listener to track pickups precisely
        this.pickupListener = new Listener() {
            @EventHandler(ignoreCancelled = true)
            public void onPickup(EntityPickupItemEvent event) {
                // Verify it's this agent picking up
                logger.info("[GatherBlockTask] onPickup event has fired.");
                if (event.getEntity().getUniqueId().equals(agent.getPersona().getUniqueId())) {
                    collectedItems.add(event.getItem().getUniqueId());
                }
            }
        };
        Bukkit.getPluginManager().registerEvents(pickupListener, IonNerrus.getInstance());
        // Bind cancellation logic
        this.cancelRegistration = token.onCancel(() -> {
            cleanup();
            if (!taskFuture.isDone()) {
                logger.info("[GatherBlockTask] Cancelled by token.");
                taskFuture.cancel(true);
            }
        });
        // Ensure registration is closed when task completes
        taskFuture.whenComplete((v, ex) -> {
            if (cancelRegistration != null) {
                cancelRegistration.close();
            }
            cleanup();
        });
        logger.info("[GatherBlockTask] Scanning for blocks...");
        agent.executeSkill(
                new FindCollectableBlockSkill(
                        targetBlocks, searchRadius,
                        new HashSet<>(attemptedLocations)),
                token, result -> result);
        return taskFuture;
    }

    @Override
    public void tick(NerrusAgent agent, ExecutionToken token) {
        // Handle wait timer for item stability check
        if (state == State.COLLECTING && waitTicks > 0) {
            waitTicks--;
            if (waitTicks == 0) {
                // Timer expired, re-scan queue
                processNextDrop(agent, token);
            }
        }
    }

    @Override
    public void onMessage(NerrusAgent agent, Object message, ExecutionToken token) {
        if (state == State.FINISHED || taskFuture.isDone()) {
            return;
        }
        switch (message) {
            // Handle Collection Scan
            case FindCollectableBlockResult result when state == State.SCANNING -> handleScanResult(agent, token, result);
            // Handle Standing Location Approach
            case NavigateToLocationResult result when state == State.APPROACHING -> handleNavigationResult(agent, token, result);
            case MovementResult result when state == State.APPROACHING -> handleEngageResult(agent, token, result);
            // Handle Equip Tool
            case EquipBestToolResult result when state == State.EQUIPPING -> handleEquipResult(agent, token, result);
            // Handle Block Break
            case BreakBlockResult result when state == State.BREAKING -> handleBreakResult(agent, token, result);
            // Handle Collection
            case MovementResult result when state == State.COLLECTING -> handleCollectionEngageResult(agent, token, result);
            case NavigateToLocationResult result when state == State.COLLECTING -> handleCollectionPathingResult(agent, token, result);
            // Added: Handle Vantage Point Recovery
            case VantagePointResult result when state == State.COLLECTING -> handleVantagePointResult(agent, token, result);
            // Handle Error
            case SystemError error -> {
                logger.severe("[GatherBlockTask] System Error received: " + error.error().getMessage());
                error.error().printStackTrace();
                fail(agent, token, GatherResult.FAILED_TO_COLLECT, "Internal System Error");
            }
            default -> {
                logger.warning(String.format(
                        "[GatherBlockTask] Ignored message %s while in state %s",
                        message.getClass().getSimpleName(), state));
            }
        }
    }

    private void handleScanResult(NerrusAgent agent, ExecutionToken token, FindCollectableBlockResult result) {
        switch (result.status()) {
            case SUCCESS -> {
                if (result.optimalTarget().isPresent()) {
                    this.repositionAttempts = 0;
                    this.navigationRetries = 0;
                    this.mainTarget = result.optimalTarget().get();
                    this.currentBreakTarget = this.mainTarget.blockLocation();
                    logger.info("[GatherBlockTask] Target found at " + mainTarget.blockLocation());
                    attemptedLocations.add(mainTarget.blockLocation()); // Mark as attempted to prevent infinite loops
                    this.state = State.APPROACHING;
                    agent.executeSkill(
                            new NavigateToLocationSkill(
                                    mainTarget.pathToStandingLocation(),
                                    mainTarget.blockLocation()),
                            token, res -> res);
                } else {
                    logger.severe("[GatherBlockTask] Critical Logic Error: Skill returned SUCCESS but no target present.");
                    fail(agent, token, GatherResult.NO_BLOCKS_IN_RANGE, "Internal logic error.");
                }
            }
            case NO_TARGETS_FOUND -> fail(agent, token, GatherResult.NO_BLOCKS_IN_RANGE, "No valid blocks found.");
            case NO_STANDPOINTS_FOUND -> fail(agent, token, GatherResult.NO_REACHABLE_BLOCKS_IN_RANGE, "No valid standing spots.");
            case NO_PATH_FOUND -> fail(agent, token, GatherResult.NO_REACHABLE_BLOCKS_IN_RANGE, "Pathfinding failed.");
        }
    }

    private void handleNavigationResult(NerrusAgent agent, ExecutionToken token, NavigateToLocationResult result) {
        if (result == NavigateToLocationResult.SUCCESS) {
            // Navigation complete, start the breaking sequence (Equip -> Break)
            startBreakingSequence(agent, token, currentBreakTarget);
        } else if (result == NavigateToLocationResult.STUCK) {
            // Retry logic for Tether Snaps / Physics Glitches
            if (navigationRetries < MAX_NAVIGATION_RETRIES) {
                navigationRetries++;
                logger.info(String.format("[GatherBlockTask] Navigation stuck (Attempt %d/%d). Retrying path...",
                        navigationRetries, MAX_NAVIGATION_RETRIES));
                // Extract destination from the original path to force a fresh pathfinding calculation
                Path oldPath = mainTarget.pathToStandingLocation();
                Location destination = oldPath.getPointAtDistance(oldPath.getLength());
                agent.executeSkill(
                        new NavigateToLocationSkill(
                                destination,
                                mainTarget.blockLocation()),
                        token, res -> res);
            } else {
                fail(agent, token, GatherResult.NO_REACHABLE_BLOCKS_IN_RANGE, "Navigation stuck after retries.");
            }
        } else {
            fail(agent, token, GatherResult.NO_REACHABLE_BLOCKS_IN_RANGE, "Navigation failed: " + result);
        }
    }

    private void handleEngageResult(NerrusAgent agent, ExecutionToken token, MovementResult result) {
        // For micro-adjustments, STUCK is actually acceptable (it means we pushed as far as we could)
        if (result == MovementResult.SUCCESS || result == MovementResult.STUCK) {
            // We moved closer, try breaking again
            startBreakingSequence(agent, token, currentBreakTarget);
        } else {
            fail(agent, token, GatherResult.NO_REACHABLE_BLOCKS_IN_RANGE, "Repositioning failed: " + result);
        }
    }

    private void startBreakingSequence(NerrusAgent agent, ExecutionToken token, Location target) {
        this.state = State.EQUIPPING;
        this.currentBreakTarget = target;
        agent.executeSkill(
                new EquipBestToolSkill(target.getBlock()),
                token, res -> res);
    }

    private void handleEquipResult(NerrusAgent agent, ExecutionToken token, EquipBestToolResult result) {
        if (result.status() == EquipBestToolResult.Status.SUCCESS) {
            this.state = State.BREAKING;
            agent.executeSkill(
                    new BreakBlockSkill(currentBreakTarget),
                    token, res -> res);
        } else {
            fail(agent, token, GatherResult.FAILED_TO_COLLECT, "Failed to equip tool.");
        }
    }

    private void handleBreakResult(NerrusAgent agent, ExecutionToken token, BreakBlockResult result) {
        switch (result.status()) {
            case SUCCESS, ALREADY_BROKEN -> {
                // Handle Obstruction Logic
                if (obstructionDepth > 0) {
                    obstructionDepth--;
                    logger.info("[GatherBlockTask] Obstruction cleared. Resuming main target.");
                    startBreakingSequence(agent, token, mainTarget.blockLocation());
                    return; // Done for this tick
                }
                // Handle Failure Case
                if (result.status() == BreakBlockResult.Status.ALREADY_BROKEN) {
                    fail(agent, token, GatherResult.FAILED_TO_COLLECT, "Break failed: " + result.status());
                    return; // Done for this tick
                }
                // Handle Success
                logger.info("[GatherBlockTask] Block broken. Processing drops...");
                // Populate queue with captured drops
                if (result.droppedItems() != null && !result.droppedItems().isEmpty()) {
                    hadDrops = true; // Mark that we actually generated items
                    pendingDrops.addAll(result.droppedItems());
                    logger.info("[GatherBlockTask] " + result.droppedItems().size() + " items dropped.");
                }
                // Transition to Collection Loop
                this.state = State.COLLECTING;
                processNextDrop(agent, token);
            }
            case OUT_OF_REACH -> {
                if (repositionAttempts >= MAX_REPOSITION_ATTEMPTS) {
                    fail(agent, token, GatherResult.FAILED_TO_COLLECT, "Target persistently out of reach.");
                    return;
                }
                repositionAttempts++;
                double maxReach = agent.getPersona().getPhysicalBody().state().getBlockReach() * 0.5;
                double stopDistanceSquared = maxReach * maxReach;
                logger.info(String.format("[GatherBlockTask] Target out of reach (Attempt %d/%d). Repositioning  to %.2f blocks...",
                        repositionAttempts, MAX_REPOSITION_ATTEMPTS, stopDistanceSquared));
                this.state = State.APPROACHING;
                Location center = currentBreakTarget.clone().add(0.5, 0.0, 0.5);
                agent.executeSkill(
                        new EngageLocationSkill(
                                center,
                                stopDistanceSquared),
                        token, res -> res);
            }
            case OBSTRUCTED -> {
                if (obstructionDepth < MAX_OBSTRUCTION_DEPTH && result.obstruction().isPresent()) {
                    obstructionDepth++;
                    startBreakingSequence(agent, token, result.obstruction().get());
                } else {
                    fail(agent, token, GatherResult.FAILED_TO_COLLECT, "Target obstructed.");
                }
            }
            default -> fail(agent, token, GatherResult.FAILED_TO_COLLECT, "Break failed: " + result.status());
        }
    }

    // Collection Loop
    private void processNextDrop(NerrusAgent agent, ExecutionToken token) {
        if (!token.isActive()) {
            return;
        }
        // Check if PREVIOUS target was collected before polling next
        if (currentDropTarget != null &&
                collectedItems.contains(currentDropTarget.getUniqueId())) {
            collectedAny = true;
        }
        // Poll next item
        currentDropTarget = pendingDrops.poll();
        // Queue Empty? Done.
        if (currentDropTarget == null) {
            // Check if we failed to collect anything despite drops existing
            if (hadDrops && !collectedAny) {
                fail(agent, token, GatherResult.FAILED_TO_COLLECT, "Failed to collect any drops.");
                return;
            }
            finish();
            return;
        }
        // Check if already collected via event listener (Instant Pickup)
        if (collectedItems.contains(currentDropTarget.getUniqueId())) {
            collectedAny = true;
            processNextDrop(agent, token);
            return;
        }
        logger.info("[GatherBlockTask] Item was not yet collected, thinking about collection method...");
        // Validate Item (Merge/Despawn Check)
        if (!currentDropTarget.isValid() || currentDropTarget.isDead()) {
            // Item merged or despawned. Skip.
            processNextDrop(agent, token);
            return;
        }
        // Reset collection retries for new item
        this.collectionRetries = 0;
        // Check if this specific item failed Fast Mode previously
        boolean forceSlowMode = failedFastModeTargets.contains(currentDropTarget.getEntityId());
        double distSq = agent.getPersona().getLocation().distanceSquared(currentDropTarget.getLocation());
        if (!forceSlowMode && distSq < FAST_MODE_DIST_SQUARED) {
            // Fast Mode: Direct Steering
            logger.info("[GatherBlockTask] processNextDrop -> Fast Mode: Direct Steering.");
            agent.executeSkill(
                    new EngageEntitySkill(
                            currentDropTarget,
                            0.25),
                    token, res -> res);
        } else {
            // Slow Mode: Pathfinding
            if (forceSlowMode) {
                logger.info("[GatherBlockTask] Forcing Slow Mode for item due to previous failure.");
            }
            // If it's far and not on ground, it's unstable.
            boolean isStable = currentDropTarget.isOnGround() || currentDropTarget.getLocation().getBlock().isLiquid();
            if (!isStable) {
                logger.info("[GatherBlockTask] Item unstable (falling). Deferring...");
                pendingDrops.offer(currentDropTarget); // Put back in queue
                currentDropTarget = null;
                waitTicks = 6;
                return;
            }
            logger.info("[GatherBlockTask] processNextDrop -> Slow Mode: Calculating Vantage Point.");
            agent.executeSkill(
                    new FindItemVantagePointSkill(currentDropTarget),
                    token,
                    VantagePointResult::new // Map Optional<Path> to record
            );
        }
    }

    // Handle Fast Mode Result
    private void handleCollectionEngageResult(NerrusAgent agent, ExecutionToken token, MovementResult result) {
        if (result == MovementResult.SUCCESS || result == MovementResult.TARGET_LOST) {
            // Check immediately - by the time we receive this result, the pickup event
            // has already fired (NMS entity tick runs before Bukkit scheduler)
            if (collectedItems.contains(currentDropTarget.getUniqueId())) {
                collectedAny = true;
                logger.info("[GatherBlockTask] Item collected successfully.");
                this.state = State.COLLECTING;
                processNextDrop(agent, token);
            } else if (!currentDropTarget.isValid() || currentDropTarget.isDead()) {
                // Item is gone but not in our collection set - despawned or merged
                logger.info("[GatherBlockTask] Item disappeared (despawn/merge). Skipping.");
                collectedAny = true; // Item is gone, assume collected
                this.state = State.COLLECTING;
                processNextDrop(agent, token);
            } else {
                // Item still exists but we "reached" it - inventory full?
                logger.warning("[GatherBlockTask] Reached item but failed to pick up (Inventory full?).");
                this.state = State.COLLECTING;
                processNextDrop(agent, token);
            }
        } else if (result == MovementResult.STUCK ||
                result == MovementResult.UNREACHABLE ||
                result == MovementResult.REPATH_NEEDED) {
            // Retry logic for Fast Mode glitches
            if (result == MovementResult.STUCK && collectionRetries < MAX_COLLECTION_RETRIES) {
                collectionRetries++;
                logger.info(String.format("[GatherBlockTask] Collection stuck (Attempt %d/%d). Retrying approach...",
                        collectionRetries, MAX_COLLECTION_RETRIES));
                agent.executeSkill(
                        new EngageEntitySkill(
                                currentDropTarget,
                                0.25),
                        token, res -> res);
                return;
            }
            // Fast mode failed. Record failure to prevent oscillation and fallback to Slow Mode.
            if (failedFastModeTargets.contains(currentDropTarget.getEntityId())) {
                // We already tried pathfinding, and it led us back here (Stuck).
                // This item is physically unreachable despite pathfinding success.
                logger.warning("[GatherBlockTask] Item unreachable (Oscillation detected). Skipping.");
                processNextDrop(agent, token);
            } else {
                logger.info("[GatherBlockTask] Fast mode failed. Promoting to Pathfinding.");
                failedFastModeTargets.add(currentDropTarget.getEntityId());
                pendingDrops.add(currentDropTarget);
                // Re-process to trigger Slow Mode logic
                processNextDrop(agent, token);
            }
        } else {
            // Cancelled or other failure
            processNextDrop(agent, token);
        }
    }

    // Handle the result of the vantage point search
    private void handleVantagePointResult(NerrusAgent agent, ExecutionToken token, VantagePointResult result) {
        if (result.path().isEmpty()) {
            logger.warning("[GatherBlockTask] Smart Recovery failed: No valid path to any vantage point found.");
            processNextDrop(agent, token);
            return;
        }
        Path path = result.path().get();
        if (DEBUG_VISUALIZATION) {
            // Visualize the path end point
            Location endPoint = path.getPointAtDistance(path.getLength());
            DebugVisualizer.highlightBlock(endPoint, 30, NamedTextColor.GREEN);
        }
        logger.info("[GatherBlockTask] Vantage point path found. Navigating...");
        // Explicitly pass the item location as the look target
        agent.executeSkill(
                new NavigateToLocationSkill(
                        path,
                        currentDropTarget.getLocation()),
                token, res -> res);
    }

    // Handle Slow Mode Result (Navigation to Vantage Point)
    private void handleCollectionPathingResult(NerrusAgent agent, ExecutionToken token, NavigateToLocationResult result) {
        if (result == NavigateToLocationResult.SUCCESS) {
            // We navigated to the Vantage Point.
            // Now trigger Fast Mode (EngageEntitySkill) to "Lunge" at the item.
            // Since we are close, Fast Mode logic will handle the final approach.
            logger.warning("[GatherBlockTask] Vantage point reached via pathfinding. Trying to engage.");
            agent.executeSkill(
                    new EngageEntitySkill(
                            currentDropTarget,
                            0.25),
                    token, res -> res);
        } else {
            // Pathfinding to the vantage point failed.
            // Since we already calculated a valid spot, this means the area is truly unreachable.
            logger.warning("[GatherBlockTask] Vantage point unreachable via pathfinding. Skipping item.");
            processNextDrop(agent, token);
        }
    }

    private void fail(NerrusAgent agent, ExecutionToken token, GatherResult reason, String logMessage) {
        logger.warning("[GatherBlockTask] " + logMessage);
        agent.postMessage(token, new GatherAttemptResult(reason));
        finish();
    }

    /**
     * Completes the task successfully.
     */
    private void finish() {
        this.state = State.FINISHED;
        cleanup();
        if (!taskFuture.isDone()) {
            taskFuture.complete(null);
        }
    }

    // Centralized cleanup method
    private void cleanup() {
        this.pendingDrops.clear();
        this.collectedItems.clear();
        if (pickupListener != null) {
            HandlerList.unregisterAll(pickupListener);
            pickupListener = null;
        }
    }
}