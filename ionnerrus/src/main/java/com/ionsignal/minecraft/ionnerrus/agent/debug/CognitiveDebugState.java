package com.ionsignal.minecraft.ionnerrus.agent.debug;

import com.ionsignal.minecraft.ioncore.debug.DebugStateSnapshot;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
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
        List<ChatMessage> conversationHistory,
        String lastToolCall,
        String lastToolResult,
        int cognitiveStepCount) implements DebugStateSnapshot {

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
     */
    public static CognitiveDebugState snapshot(
            com.ionsignal.minecraft.ionnerrus.agent.llm.ReActDirector director,
            com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent agent) {
        return new CognitiveDebugState(
                agent.getPersona().getUniqueId(),
                agent.getName(),
                director.getDirective(),
                List.copyOf(director.getConversationHistory()),
                director.getLastToolCall(),
                director.getLastToolResult(),
                director.getCognitiveStepCount());
    }
}