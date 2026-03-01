package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.following;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common.FindTargetEntitySkill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.body.PerceptionUpdate;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.system.SystemError;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.system.TaskCompleted;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.system.TimeoutUpdate;
import com.ionsignal.minecraft.ionnerrus.agent.directors.tools.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.content.BlockTagManager;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FollowPlayerGoal implements Goal {
    private enum State {
        SEARCHING, FOLLOWING, COMPLETED, FAILED
    }

    private final FollowPlayerParameters params;
    private final long durationTicks;

    private GoalResult finalResult;
    private BukkitTask timeoutTask;
    private State state = State.SEARCHING;

    public FollowPlayerGoal(FollowPlayerParameters params) {
        this.params = params;
        this.durationTicks = (long) (params.duration() * 20.0);
    }

    @Override
    public void start(NerrusAgent agent, ExecutionToken token) {
        agent.speak(String.format("Okay, I'll follow %s for %d seconds.", params.targetName(), Math.round(params.duration())));
        // Schedule the timeout immediately
        this.timeoutTask = Bukkit.getScheduler().runTaskLater(
                IonNerrus.getInstance(), () -> {
                    if (token.isActive()) {
                        agent.postMessage(token, new TimeoutUpdate("Duration expired"));
                    }
                }, durationTicks);
        // Trigger Skill -> Bridge to Message
        startSearching(agent, token);
    }

    private void startSearching(NerrusAgent agent, ExecutionToken token) {
        agent.executeSkill(new FindTargetEntitySkill(params.targetName()), token,
                // Mapper: Convert Optional<Entity> to PerceptionUpdate
                opt -> new PerceptionUpdate(opt.isPresent(), opt.orElse(null), null));
    }

    @Override
    public void process(NerrusAgent agent, ExecutionToken token) {
        // no-op
    }

    @Override
    public void onMessage(NerrusAgent agent, Object message, ExecutionToken token) {
        switch (message) {
            case PerceptionUpdate msg -> {
                if (msg.found() && msg.targetEntity() instanceof LivingEntity target) {
                    // Target found, start physical follow
                    this.state = State.FOLLOWING;
                    // Track the target visually
                    agent.getPersona().getPhysicalBody().orientation().face(target, token);
                    // Assign the continuous task instead of executing a skill directly
                    agent.setCurrentTask(new FollowEntityTask(
                            target,
                            params.followDistance(),
                            params.stopDistance()));
                } else {
                    fail("I can't find " + params.targetName() + ".");
                }
            }
            case TimeoutUpdate msg -> {
                complete(String.format("I followed %s for %.1f seconds as requested.",
                        params.targetName(), params.duration()));
            }
            case TaskCompleted event -> {
                if (state == State.FOLLOWING) {
                    // If the FollowEntityTask finishes before the timeout, it usually means
                    // the target was lost, the agent got stuck, or the target died.
                    if (event.error().isPresent()) {
                        // This handles teleportation, respawning, or temporary chunk unloading.
                        agent.speak("Hold on, I lost you.");
                        this.state = State.SEARCHING;
                        startSearching(agent, token);
                    } else {
                        fail("I lost sight of the target.");
                    }
                }
            }
            case SystemError msg -> {
                fail("An error occurred: " + msg.error().getMessage());
            }
            default -> {
            }
        }
    }

    private void complete(String message) {
        cancelTimeout();
        this.finalResult = new GoalResult.Success(message);
        this.state = State.COMPLETED;
    }

    private void fail(String message) {
        cancelTimeout();
        this.finalResult = new GoalResult.Failure(message);
        this.state = State.FAILED;
    }

    private void cancelTimeout() {
        if (timeoutTask != null && !timeoutTask.isCancelled()) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    @Override
    public boolean isFinished() {
        return state == State.COMPLETED || state == State.FAILED;
    }

    @Override
    public void stop(NerrusAgent agent) {
        cancelTimeout();
        if (!isFinished()) {
            this.finalResult = new GoalResult.Failure("Follow goal was cancelled.");
            this.state = State.FAILED;
        }
    }

    @Override
    public GoalResult getFinalResult() {
        return finalResult;
    }

    public static class Provider implements GoalProvider {
        @Override
        public ToolDefinition getToolDefinition(BlockTagManager blockTagManager) {
            return new ToolDefinition(
                    "FOLLOW_PLAYER",
                    "Follows a target player or agent for a specified duration. The goal completes successfully when the time limit is reached or fails if the target becomes unreachable.",
                    FollowPlayerParameters.class,
                    (schema, agent) -> {
                        AgentService agentService = IonNerrus.getInstance().getAgentService();
                        List<String> playerNames = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                        List<String> agentNames = agentService.getAgents().stream()
                                .filter(a -> !a.getPersona().getUniqueId().equals(agent.getPersona().getUniqueId()))
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
                            ObjectNode durationProp = (ObjectNode) properties.get("duration");
                            if (durationProp != null) {
                                durationProp.put("minimum", 1.0);
                                durationProp.put("maximum", 600.0);
                            }
                        }
                        return schema;
                    });
        }
    }
}