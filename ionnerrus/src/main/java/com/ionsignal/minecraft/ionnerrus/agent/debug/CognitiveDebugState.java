package com.ionsignal.minecraft.ionnerrus.agent.debug;

import com.ionsignal.minecraft.ioncore.debug.DebugStateSnapshot;
import com.ionsignal.minecraft.ionnerrus.agent.llm.ReActDirector;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

import com.openai.models.chat.completions.ChatCompletionMessageParam;

import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of ReActDirector cognitive state for debugging.
 * Thread Safety: All fields are immutable or defensive copies.
 */
public record CognitiveDebugState(
        UUID agentId,
        String agentName,
        String currentDirective,
        List<ChatCompletionMessageParam> conversationHistory,
        String lastToolCall,
        String lastToolResult,
        int cognitiveStepCount,
        String pendingRequestSummary) implements DebugStateSnapshot {

    /**
     * Implement DebugStateSnapshot marker interface
     */
    @Override
    public String getDebugLabel() {
        return "Cognitive Reasoning: " + agentName;
    }

    /**
     * Creates a snapshot from a live ReActDirector.
     * MUST be called on the main thread to avoid race conditions.
     *
     * @param director
     *            The director instance.
     * @param agent
     *            The agent instance.
     * @param pendingRequestSummary
     *            A summary of the next LLM request, or null if not applicable.
     */
    public static CognitiveDebugState snapshot(
            ReActDirector director,
            NerrusAgent agent,
            String pendingRequestSummary) {
        List<ChatCompletionMessageParam> conversationHistory = List.copyOf(director.getConversationHistory());
        return new CognitiveDebugState(
                agent.getPersona().getUniqueId(),
                agent.getName(),
                director.getDirective(),
                conversationHistory,
                director.getLastToolCall(),
                director.getLastToolResult(),
                director.getCognitiveStepCount(),
                pendingRequestSummary);
    }
}