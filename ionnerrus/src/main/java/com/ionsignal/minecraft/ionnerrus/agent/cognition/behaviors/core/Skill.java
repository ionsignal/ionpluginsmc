package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core;

import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a single, atomic action an agent can perform.
 * All skills are executed asynchronously from the caller's perspective.
 *
 * @param <R>
 *            The result type of the skill.
 */
@FunctionalInterface
public interface Skill<R> {
    /**
     * Executes the skill.
     *
     * @param agent
     *            The agent performing the skill.
     * @param token
     *            The execution token. The skill MUST pass this to PhysicalBody methods
     *            or check token.throwIfCancelled() during long-running async operations.
     * @return A CompletableFuture that will be completed with the skill's result.
     */
    CompletableFuture<R> execute(NerrusAgent agent, ExecutionToken token);
}