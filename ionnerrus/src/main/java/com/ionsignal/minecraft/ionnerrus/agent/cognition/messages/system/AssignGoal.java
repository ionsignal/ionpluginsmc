package com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.system;

import java.util.concurrent.CompletableFuture;

import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalResult;

public record AssignGoal(Goal goal, Object parameters, CompletableFuture<GoalResult> resultFuture) {
}