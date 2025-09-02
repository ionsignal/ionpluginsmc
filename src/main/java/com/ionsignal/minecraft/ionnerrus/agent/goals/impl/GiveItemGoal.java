package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GiveItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CheckPlayerReadySkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.DropItemSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindTargetEntitySkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.NavigateToLocationSkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.action.impl.FaceHeadBodyAction;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationHelper;
// import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.NavigateToLocationResult;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class GiveItemGoal implements Goal {
    private enum State {
        CHECKING_INVENTORY, FINDING_TARGET, NAVIGATING_NEAR_TARGET, WAITING_FOR_PLAYER, ENGAGING_TARGET, DROPPING_ITEM, COMPLETED, FAILED
    }

    private static final String TARGET_ENTITY_KEY = "giveItem.targetEntity";
    private static final double ENGAGE_THRESHOLD_SQUARED = 10.0 * 10.0;

    private final Logger logger;
    private final GiveItemParameters params;
    private final Material material;
    private State state = State.CHECKING_INVENTORY;
    private GoalResult finalResult;
    private FaceHeadBodyAction faceAction;

    public GiveItemGoal(GiveItemParameters params, Material material) {
        this.params = params;
        this.material = material;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public void start(NerrusAgent agent) {
        agent.speak("Okay, I'll give " + params.quantity() + " " + params.materialName() + " to " + params.targetName() + ".");
    }

    @Override
    public void process(NerrusAgent agent) {
        if (isFinished()) {
            return;
        }
        switch (state) {
            case CHECKING_INVENTORY:
                logger.info("GiveItemGoal: Checking inventory for " + params.quantity() + " " + material.name());
                agent.setCurrentTask(createSkillTask(new CountItemsSkill(Set.of(material)).execute(agent)
                        .thenAccept(count -> {
                            if (count >= params.quantity()) {
                                state = State.FINDING_TARGET;
                            } else {
                                fail("I don't have enough " + params.materialName() + " to give.");
                            }
                        })));
                break;

            case FINDING_TARGET:
                logger.info("GiveItemGoal: Looking for target '" + params.targetName() + "'.");
                agent.setCurrentTask(createSkillTask(new FindTargetEntitySkill(params.targetName()).execute(agent)
                        .thenAccept(entityOpt -> {
                            if (entityOpt.isPresent()) {
                                agent.getBlackboard().put(TARGET_ENTITY_KEY, entityOpt.get());
                                state = State.NAVIGATING_NEAR_TARGET;
                            } else {
                                fail("I can't find " + params.targetName() + ".");
                            }
                        })));
                break;

            case NAVIGATING_NEAR_TARGET:
                agent.getBlackboard().get(TARGET_ENTITY_KEY, LivingEntity.class).ifPresentOrElse(target -> {
                    Location targetLocation = target.getLocation();
                    Optional<Location> groundLocationOpt = NavigationHelper.findGround(targetLocation, 15);
                    if (groundLocationOpt.isEmpty()) {
                        fail("I can't find a safe spot to navigate to near " + params.targetName() + ".");
                        return; // Exit the lambda
                    }
                    Location safeNavTarget = groundLocationOpt.get();
                    logger.info("GiveItemGoal: Navigating to " + safeNavTarget.toVector());
                    agent.setCurrentTask(createSkillTask(new NavigateToLocationSkill(safeNavTarget).execute(agent)
                            .thenAccept(navResult -> {
                                if (navResult == NavigateToLocationResult.SUCCESS) {
                                    double distanceSq = agent.getPersona().getLocation().distanceSquared(target.getLocation());
                                    if (distanceSq < ENGAGE_THRESHOLD_SQUARED) {
                                        // The target is close enough, proceed to engage.
                                        logger.info("GiveItemGoal: Arrived near target, proceeding to engage.");
                                        state = State.WAITING_FOR_PLAYER;
                                    } else {
                                        // We arrived, but the target has moved too far away. Loop back to re-navigate.
                                        logger.info("GiveItemGoal: Arrived at last known location, but target has moved. Re-navigating.");
                                        state = State.NAVIGATING_NEAR_TARGET;
                                    }
                                } else {
                                    fail("I can't find a path to " + params.targetName() + ".");
                                }
                            })));
                }, () -> fail("Lost track of the target entity."));
                break;

            case WAITING_FOR_PLAYER:
                agent.getBlackboard().get(TARGET_ENTITY_KEY, LivingEntity.class).ifPresentOrElse(target -> {
                    logger.info("GiveItemGoal: Waiting for " + target.getName() + " to be ready.");
                    // Announce intent and start looking at the player
                    faceAction = new FaceHeadBodyAction(target, true, Integer.MAX_VALUE);
                    agent.getPersona().getActionController().schedule(faceAction);
                    agent.speak("Hey, " + params.targetName() + " come over here! I have something for you!");
                    agent.setCurrentTask(createSkillTask(new CheckPlayerReadySkill(target, 15).execute(agent)
                            .thenAccept(isReady -> {
                                if (isReady) {
                                    state = State.DROPPING_ITEM;
                                } else {
                                    agent.speak("Oh, looks like you're busy. I'll hold onto this for you");
                                    fail("Persona did not drop the items.");
                                }
                            })));
                }, () -> fail("Lost track of the target entity."));
                break;

            case DROPPING_ITEM:
                logger.info("GiveItemGoal: Dropping " + params.quantity() + " " + material.name());
                agent.getBlackboard().get(TARGET_ENTITY_KEY, LivingEntity.class).ifPresentOrElse(target -> {
                    logger.info("GiveItemGoal: Dropping " + params.quantity() + " " + material.name());
                    // CHANGE: Pass the target entity to the DropItemSkill for the calculated toss.
                    agent.setCurrentTask(createSkillTask(new DropItemSkill(material, params.quantity(), target).execute(agent)
                            .thenAccept(success -> {
                                if (success) {
                                    agent.speak("Here you go, " + params.targetName() + "!");
                                    succeed("Successfully gave " + params.quantity() + " " + material.name() + " to " + params.targetName()
                                            + ".");
                                } else {
                                    fail("Persona did not drop the items.");
                                }
                            })));
                }, () -> fail("Lost track of the target entity."));
                break;

            default:
                break;
        }
    }

    private void fail(String message) {
        logger.warning("GiveItemGoal Failed: " + message);
        this.finalResult = new GoalResult(GoalResult.Status.FAILURE, message);
        this.state = State.FAILED;
        this.stopFacing();
    }

    private void succeed(String message) {
        logger.info("GiveItemGoal Succeeded: " + message);
        this.finalResult = new GoalResult(GoalResult.Status.SUCCESS, message);
        this.state = State.COMPLETED;
        this.stopFacing();
    }

    private void stopFacing() {
        if (this.faceAction != null
                && this.faceAction.getStatus() == com.ionsignal.minecraft.ionnerrus.persona.action.ActionStatus.RUNNING) {
            this.faceAction.stop();
            this.faceAction = null;
        }
    }

    private Task createSkillTask(CompletableFuture<?> future) {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                return future.thenApply(v -> null);
            }

            @Override
            public void cancel() {
                /* No-op for simple skills */ }
        };
    }

    @Override
    public boolean isFinished() {
        return state == State.COMPLETED || state == State.FAILED;
    }

    @Override
    public void stop(NerrusAgent agent) {
        if (!isFinished()) {
            fail("Goal was cancelled.");
        }
        agent.getBlackboard().remove(TARGET_ENTITY_KEY);
        stopFacing();
    }

    @Override
    public GoalResult getFinalResult() {
        return finalResult;
    }
}