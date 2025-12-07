package com.ionsignal.minecraft.ionnerrus.agent.tasks.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken.Registration;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GatherBlockGoal.GatherAttemptResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GatherBlockGoal.GatherResult;
import com.ionsignal.minecraft.ionnerrus.agent.messages.SystemError;
import com.ionsignal.minecraft.ionnerrus.agent.skills.CollectableBlock;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.BreakBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.EquipBestToolSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.EngageLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.EngageEntitySkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindCollectableBlockSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.NavigateToLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.BreakBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.EquipBestToolResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.FindCollectableBlockResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.NavigateToLocationResult;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * A reactive, state-machine based task for gathering blocks that supports fast/slow collection.
 */
public class GatherBlockTask implements Task {
    private static final int MAX_REPOSITION_ATTEMPTS = 1;
    private static final int MAX_OBSTRUCTION_DEPTH = 1;
    private static final double FAST_MODE_DIST = 3.0;
    private static final double FAST_MODE_DIST_SQUARED = FAST_MODE_DIST * FAST_MODE_DIST;

    private final Logger logger;
    private final int searchRadius;
    private final Set<Material> targetBlocks;
    private final Set<Location> attemptedLocations;

    // Task State
    private CompletableFuture<Void> taskFuture;
    private State state = State.IDLE;
    private Registration cancelRegistration;

    // Execution Context
    private int obstructionDepth = 0;
    private int repositionAttempts = 0;
    private CollectableBlock mainTarget;
    private Location currentBreakTarget;
    private Item currentDropTarget;

    // Collection State
    private final Set<Integer> failedFastModeTargets = new HashSet<>();
    private final Queue<Item> pendingDrops = new LinkedList<>();

    private enum State {
        IDLE, SCANNING, APPROACHING, EQUIPPING, BREAKING, COLLECTING, FINISHED
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
        // Bind cancellation logic
        this.cancelRegistration = token.onCancel(() -> {
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
                if (obstructionDepth > 0) {
                    obstructionDepth--;
                    logger.info("[GatherBlockTask] Obstruction cleared. Resuming main target.");
                    startBreakingSequence(agent, token, mainTarget.blockLocation());
                } else {
                    logger.info("[GatherBlockTask] Block broken. Processing drops...");
                    // Populate queue with captured drops
                    if (result.droppedItems() != null && !result.droppedItems().isEmpty()) {
                        pendingDrops.addAll(result.droppedItems());
                        logger.info("[GatherBlockTask] " + result.droppedItems().size() + " items dropped.");
                    }
                    // Transition to Collection Loop
                    this.state = State.COLLECTING;
                    processNextDrop(agent, token);
                }
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
        // Poll next item
        currentDropTarget = pendingDrops.poll();
        // Queue Empty? Done.
        if (currentDropTarget == null) {
            finish();
            return;
        }
        // Validate Item (Merge/Despawn Check)
        if (!currentDropTarget.isValid() || currentDropTarget.isDead()) {
            // Item merged or despawned. Skip.
            processNextDrop(agent, token);
            return;
        }
        // Check if this specific item failed Fast Mode previously
        boolean forceSlowMode = failedFastModeTargets.contains(currentDropTarget.getEntityId());
        double distSq = agent.getPersona().getLocation().distanceSquared(currentDropTarget.getLocation());
        if (!forceSlowMode && distSq < FAST_MODE_DIST_SQUARED) {
            // Fast Mode: Direct Steering
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
            agent.executeSkill(
                    new NavigateToLocationSkill(currentDropTarget.getLocation()),
                    token, res -> res);
        }
    }

    // Handle Fast Mode Result
    private void handleCollectionEngageResult(NerrusAgent agent, ExecutionToken token, MovementResult result) {
        if (result == MovementResult.SUCCESS) {
            // We arrived. Verify pickup.
            if (currentDropTarget.isValid() && !currentDropTarget.isDead()) {
                // Item still exists after we stood on it. Inventory likely full.
                logger.warning("[GatherBlockTask] Failed to pick up item (Inventory Full?). Skipping.");
            }
            // Loop
            processNextDrop(agent, token);
        } else if (result == MovementResult.STUCK ||
                result == MovementResult.UNREACHABLE ||
                result == MovementResult.REPATH_NEEDED) {
            // Fast mode failed. Record failure to prevent oscillation and fallback to Slow Mode.
            logger.info("[GatherBlockTask] Fast mode failed (" + result + "). Promoting to Pathfinding.");
            failedFastModeTargets.add(currentDropTarget.getEntityId());
            agent.executeSkill(
                    new NavigateToLocationSkill(
                            currentDropTarget.getLocation()),
                    token, res -> res);
        } else {
            // Cancelled or other failure
            processNextDrop(agent, token);
        }
    }

    // Handle Slow Mode Result
    private void handleCollectionPathingResult(NerrusAgent agent, ExecutionToken token, NavigateToLocationResult result) {
        if (result == NavigateToLocationResult.SUCCESS) {
            // We navigated close to the item. Now trigger Fast Mode to finalize the pickup.
            // Note: We do NOT add to failedFastModeTargets here because we are already close enough
            // that Fast Mode should work (or fail immediately if inventory is full).
            agent.executeSkill(
                    new EngageEntitySkill(
                            currentDropTarget,
                            0.25),
                    token, res -> res);
        } else {
            // Pathfinding failed. Item unreachable. Skip.
            logger.warning("[GatherBlockTask] Item unreachable via pathfinding. Skipping.");
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
        this.pendingDrops.clear(); // Cleanup references
        if (!taskFuture.isDone()) {
            taskFuture.complete(null);
        }
    }
}