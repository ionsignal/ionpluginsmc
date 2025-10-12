package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GiveItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CheckPlayerReadySkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.CountItemsSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.DropItemSkill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindTargetEntitySkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.EngageResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class GiveItemGoal implements Goal {
    private enum State {
        CHECKING_INVENTORY, FINDING_TARGET, APPROACHING_AND_WAITING, FOLLOWING_AND_WAITING, DROPPING_ITEM, COMPLETED, FAILED
    }

    private static final String TARGET_ENTITY_KEY = "giveItem.targetEntity";

    private final Logger logger;
    private final GiveItemParameters params;
    private final Material material;
    private State state = State.CHECKING_INVENTORY;
    private GoalResult finalResult;

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
                        .thenAccept(counts -> {
                            int haveAmount = counts.getOrDefault(material, 0);
                            if (haveAmount >= params.quantity()) {
                                state = State.FINDING_TARGET;
                            } else {
                                fail(agent, "I don't have enough " + params.materialName() + " to give. I only have " + haveAmount + ".");
                            }
                        })));
                break;

            case FINDING_TARGET:
                logger.info("GiveItemGoal: Looking for target '" + params.targetName() + "'.");
                agent.setCurrentTask(createSkillTask(new FindTargetEntitySkill(params.targetName()).execute(agent)
                        .thenAccept(entityOpt -> {
                            if (entityOpt.isPresent()) {
                                agent.getBlackboard().put(TARGET_ENTITY_KEY, entityOpt.get());
                                state = State.APPROACHING_AND_WAITING;
                            } else {
                                fail(agent, "I can't find " + params.targetName() + ".");
                            }
                        })));
                break;

            // Initiate the parallel follow and check operations
            case APPROACHING_AND_WAITING:
                agent.getBlackboard().get(TARGET_ENTITY_KEY, LivingEntity.class).ifPresentOrElse(target -> {
                    logger.info("GiveItemGoal: Approaching and waiting for " + target.getName() + " to be ready.");
                    // Start the continuous follow behavior. The Navigator will handle all movement.
                    agent.getPersona().getNavigator().followOn(target, 5.0, 2.5);
                    agent.speak("Hey, " + params.targetName() + "! I have something for you!");
                    // Simultaneously, start checking if the player is ready for the interaction.
                    agent.setCurrentTask(createSkillTask(new CheckPlayerReadySkill(target, 20).execute(agent) // Increased timeout
                            .thenAccept(isReady -> {
                                // This is the crucial cleanup step. We MUST stop the follow behavior
                                // before proceeding, regardless of the outcome.
                                agent.getPersona().getNavigator().cancelCurrentOperation(NavigationResult.CANCELLED,
                                        EngageResult.CANCELLED);
                                if (isReady) {
                                    state = State.DROPPING_ITEM;
                                } else {
                                    agent.speak("Oh, looks like you're busy. I'll hold onto this for you.");
                                    fail(agent, "Player was not ready for the interaction.");
                                }
                            })));
                    // Transition to a new "do-nothing" state to prevent this block from running again.
                    state = State.FOLLOWING_AND_WAITING;
                }, () -> fail(agent, "Lost track of the target entity."));
                break;

            // State that does nothing, simply waiting for the CheckPlayerReadySkill future to complete.
            case FOLLOWING_AND_WAITING:
                // The agent's task is running (CheckPlayerReadySkill).
                // The Navigator is running (follow).
                // The Goal's job here is to do nothing and wait for one of those to finish.
                break;

            case DROPPING_ITEM:
                logger.info("GiveItemGoal: Dropping " + params.quantity() + " " + material.name());
                agent.getBlackboard().get(TARGET_ENTITY_KEY, LivingEntity.class).ifPresentOrElse(target -> {
                    // Pass the target entity to the DropItemSkill for the calculated toss.
                    agent.setCurrentTask(createSkillTask(new DropItemSkill(material, params.quantity(), target).execute(agent)
                            .thenAccept(success -> {
                                if (success) {
                                    agent.speak("Here you go, " + params.targetName() + "!");
                                    succeed(agent,
                                            "Successfully gave " + params.quantity() + " " + material.name() + " to " + params.targetName()
                                                    + ".");
                                } else {
                                    fail(agent, "I couldn't seem to drop the items from my inventory.");
                                }
                            })));
                }, () -> fail(agent, "Lost track of the target entity."));
                break;

            default:
                break;
        }
    }

    private void fail(NerrusAgent agent, String message) {
        logger.warning("GiveItemGoal Failed: " + message);
        this.finalResult = new GoalResult.Failure(message);
        this.state = State.FAILED;
        if (agent != null && agent.getPersona().getNavigator().isBusy()) {
            agent.getPersona().getNavigator().cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
        }
    }

    private void succeed(NerrusAgent agent, String message) {
        logger.info("GiveItemGoal Succeeded: " + message);
        this.finalResult = new GoalResult.Success(message);
        this.state = State.COMPLETED;
        if (agent != null && agent.getPersona().getNavigator().isBusy()) {
            agent.getPersona().getNavigator().cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
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
        // We must ensure the navigator's follow state is cancelled if the goal is stopped externally.
        if (agent.getPersona().getNavigator().isBusy()) {
            agent.getPersona().getNavigator().cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
        }
        if (!isFinished()) {
            fail(agent, "Goal was cancelled.");
        }
        agent.getBlackboard().remove(TARGET_ENTITY_KEY);
    }

    @Override
    public GoalResult getFinalResult() {
        return finalResult;
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "GIVE_ITEM",
                    "Gives a specified quantity of an item to a target player or another agent.",
                    GiveItemParameters.class,
                    (schema, agent) -> {
                        AgentService agentService = IonNerrus.getInstance().getAgentService();
                        List<String> playerNames = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                        List<String> agentNames = agentService.getAgents().stream()
                                .filter(a -> !a.getPersona().getUniqueId().equals(agent.getPersona().getUniqueId())) // Exclude self
                                .map(NerrusAgent::getName)
                                .toList();
                        String validTargets = Stream.concat(playerNames.stream(), agentNames.stream())
                                .distinct()
                                .collect(Collectors.joining(", "));
                        ObjectNode properties = (ObjectNode) schema.get("properties");
                        if (properties != null) {
                            ObjectNode targetNameProp = (ObjectNode) properties.get("targetName");
                            if (targetNameProp != null) {
                                String currentDesc = targetNameProp.get("description").asText();
                                if (validTargets.isEmpty()) {
                                    targetNameProp.put("description", currentDesc + " No available targets found.");
                                } else {
                                    targetNameProp.put("description", currentDesc + " Available targets: " + validTargets);
                                }
                            }
                        }
                        return schema;
                    });
        }
    }
}