package com.ionsignal.minecraft.ionnerrus.agent.skills;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a single, atomic action an agent can perform.
 * All skills are executed asynchronously from the caller's perspective,
 * providing their result via a CompletableFuture.
 *
 * @param <R>
 *            The result type of the skill.
 */
@FunctionalInterface
public interface Skill<R> {
    /**
     * Executes the skill. The implementation is responsible for its own threading.
     * If it's a long, synchronous operation, it should be run on an async thread.
     * If it wraps an async API, it should just delegate.
     *
     * @param agent
     *            The agent performing the skill.
     * @return A CompletableFuture that will be completed with the skill's result.
     */
    CompletableFuture<R> execute(NerrusAgent agent);
}