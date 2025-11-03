package com.ionsignal.minecraft.ionnerrus.agent.messages;

import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;

import java.util.concurrent.CompletableFuture;

public record AssignGoal(Goal goal, Object parameters, CompletableFuture<GoalResult> resultFuture) {
}