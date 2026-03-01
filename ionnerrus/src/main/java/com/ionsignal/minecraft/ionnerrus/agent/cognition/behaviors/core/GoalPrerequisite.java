package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core;

/**
 * An immutable data carrier that formally defines a sub-goal request.
 *
 * @param goalName
 *            The registered name of the goal to execute.
 * @param parameters
 *            The parameters object for the goal.
 */
public record GoalPrerequisite(String goalName, Object parameters) {
}