package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalProvider;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FollowPlayerParameters;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindTargetEntitySkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;

public class FollowPlayerGoal implements Goal {
    private enum State {
        ACQUIRING_TARGET, FOLLOWING, FINISHED
    }

    private final Object contextToken = new Object();
    private final long durationMillis;
    private final FollowPlayerParameters params;
    private volatile State state = State.ACQUIRING_TARGET;
    private volatile GoalResult finalResult;

    private BukkitTask timeoutCheckTask = null;
    private CompletableFuture<MovementResult> followFuture;
    private long followStartTimeMillis = -1;

    public FollowPlayerGoal(FollowPlayerParameters params) {
        this.params = params;
        this.durationMillis = (long) (params.duration() * 1000.0);
    }

    @Override
    public void start(NerrusAgent agent) {
        agent.speak(String.format("Okay, I'll follow %s for %.1f seconds.", params.targetName(), params.duration()));
    }

    @Override
    public void process(NerrusAgent agent) {
        if (isFinished()) {
            return;
        }
        if (state == State.ACQUIRING_TARGET) {
            agent.setCurrentTask(createSkillTask(
                    new FindTargetEntitySkill(params.targetName()).execute(agent)
                            .thenAcceptAsync(entityOpt -> {
                                if (entityOpt.isPresent()) {
                                    startFollowing(agent, entityOpt.get());
                                } else {
                                    fail("I can't find " + params.targetName() + ".");
                                }
                            }, IonNerrus.getInstance().getMainThreadExecutor())));
        }
    }

    private void startFollowing(NerrusAgent agent, LivingEntity target) {
        state = State.FOLLOWING;
        followStartTimeMillis = System.currentTimeMillis();
        // Track the target
        agent.getPersona().getPhysicalBody().orientation().face(target);
        // Start following
        followFuture = agent.getPersona().getPhysicalBody().movement()
                .follow(target, params.followDistance(), params.stopDistance());
        followFuture.whenCompleteAsync((result, throwable) -> {
            // If the goal is already finished (e.g. success via timeout), ignore this callback
            if (isFinished()) {
                return;
            }
            cancelTimeoutTask();
            if (throwable != null) {
                // Cancellation is expected if we stop manually
                if (throwable instanceof java.util.concurrent.CancellationException) {
                    return;
                }
                agent.postMessage(contextToken, new GoalResult.Failure("An error occurred while following: " + throwable.getMessage()));
            } else {
                agent.postMessage(contextToken,
                        new GoalResult.Failure("I stopped following " + params.targetName() + " because: " + result.name()));
            }
        }, IonNerrus.getInstance().getMainThreadExecutor());
        // Start Bukkit repeating task to check for timeout every tick
        timeoutCheckTask = Bukkit.getScheduler().runTaskTimer(
                IonNerrus.getInstance(), () -> {
                    if (isFinished()) {
                        cancelTimeoutTask();
                        return;
                    }
                    long elapsedMillis = System.currentTimeMillis() - followStartTimeMillis;
                    if (elapsedMillis >= durationMillis) {
                        agent.speak("I'm done following.");
                        // Stop movement via PhysicalBody
                        agent.getPersona().getPhysicalBody().movement().stop();
                        // Release orientation on success
                        agent.getPersona().getPhysicalBody().orientation().clearLookTarget();
                        // Use message queue instead of direct state mutation
                        agent.postMessage(contextToken, new GoalResult.Success(
                                String.format("I followed %s for %.1f seconds as requested.",
                                        params.targetName(),
                                        params.duration())));
                    }
                },
                0L, // Start immediately
                10L // Check every 500ms
        );
    }

    private void fail(String message) {
        this.finalResult = new GoalResult.Failure(message);
        this.state = State.FINISHED;
    }

    // Helper method to safely cancel the timeout task
    private void cancelTimeoutTask() {
        if (timeoutCheckTask != null && !timeoutCheckTask.isCancelled()) {
            timeoutCheckTask.cancel();
            timeoutCheckTask = null;
        }
    }

    @Override
    public boolean isFinished() {
        return state == State.FINISHED;
    }

    @Override
    public void stop(NerrusAgent agent) {
        cancelTimeoutTask();
        if (state == State.FOLLOWING) {
            // Stop movement via PhysicalBody
            agent.getPersona().getPhysicalBody().movement().stop();
            // Release the orientation lock
            agent.getPersona().getPhysicalBody().orientation().clearLookTarget();
        }
        if (!isFinished()) {
            fail("Follow goal was cancelled.");
        }
    }

    @Override
    public GoalResult getFinalResult() {
        return finalResult;
    }

    @Override
    public void onMessage(NerrusAgent agent, Object message) {
        // Handle async navigation results posted via agent.postMessage()
        if (message instanceof GoalResult result) {
            cancelTimeoutTask();
            this.finalResult = result;
            this.state = State.FINISHED;
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
                /* No-op */
            }
        };
    }

    @Override
    public Object getContextToken() {
        return contextToken;
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