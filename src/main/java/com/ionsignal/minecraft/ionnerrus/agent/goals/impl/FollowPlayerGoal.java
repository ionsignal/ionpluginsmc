package com.ionsignal.minecraft.ionnerrus.agent.goals.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FollowPlayerParameters;
import com.ionsignal.minecraft.ionnerrus.agent.skills.impl.FindTargetEntitySkill;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.Task;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.EngageResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;

import org.bukkit.entity.LivingEntity;

import java.util.concurrent.CompletableFuture;

public class FollowPlayerGoal implements Goal {
    private enum State {
        ACQUIRING_TARGET, FOLLOWING, FINISHED
    }

    private final FollowPlayerParameters params;
    private State state = State.ACQUIRING_TARGET;
    private GoalResult finalResult;
    private CompletableFuture<NavigationResult> followFuture;

    public FollowPlayerGoal(FollowPlayerParameters params) {
        this.params = params;
    }

    @Override
    public void start(NerrusAgent agent) {
        agent.speak("Okay, I'll follow " + params.targetName() + ".");
    }

    @Override
    public void process(NerrusAgent agent) {
        if (isFinished()) {
            return;
        }

        if (state == State.ACQUIRING_TARGET) {
            agent.setCurrentTask(createSkillTask(
                    new FindTargetEntitySkill(params.targetName()).execute(agent)
                            .thenAccept(entityOpt -> {
                                if (entityOpt.isPresent()) {
                                    startFollowing(agent, entityOpt.get());
                                } else {
                                    fail("I can't find " + params.targetName() + ".");
                                }
                            })));
        }
        // While in FOLLOWING state, this method does nothing.
        // The Navigator handles all the logic. The goal just waits for the future to complete.
    }

    private void startFollowing(NerrusAgent agent, LivingEntity target) {
        state = State.FOLLOWING;
        followFuture = agent.getPersona().getNavigator().followOn(target, params.followDistance(), params.stopDistance());
        followFuture.whenComplete((result, throwable) -> {
            if (throwable != null) {
                fail("An error occurred while following: " + throwable.getMessage());
            } else {
                // The future only completes on failure or cancellation.
                fail("I stopped following " + params.targetName() + " because: " + result.name());
            }
        });
    }

    private void fail(String message) {
        this.finalResult = new GoalResult(GoalResult.Status.FAILURE, message);
        this.state = State.FINISHED;
    }

    @Override
    public boolean isFinished() {
        return state == State.FINISHED;
    }

    @Override
    public void stop(NerrusAgent agent) {
        if (state == State.FOLLOWING) {
            agent.getPersona().getNavigator().cancelCurrentOperation(NavigationResult.CANCELLED, EngageResult.CANCELLED);
        }
        if (!isFinished()) {
            fail("Follow goal was cancelled.");
        }
    }

    @Override
    public GoalResult getFinalResult() {
        return finalResult;
    }

    private Task createSkillTask(CompletableFuture<?> future) {
        return new Task() {
            @Override
            public CompletableFuture<Void> execute(NerrusAgent agent) {
                return future.thenApply(v -> null);
            }

            @Override
            public void cancel() {
                /* No-op */ }
        };
    }
}