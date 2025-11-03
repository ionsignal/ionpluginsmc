package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.llm.context.AgentContext;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import io.github.sashirestela.openai.domain.chat.ChatRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.logging.Level;

/**
 * Handles simple, one-shot conversational queries to an agent's LLM.
 * This director builds a context-aware prompt but provides no tools, forcing
 * a direct text response from the LLM.
 */
public class AskDirector {
    private final IonNerrus plugin;
    private final LLMService llmService;

    public AskDirector(LLMService llmService) {
        this.plugin = IonNerrus.getInstance();
        this.llmService = llmService;
    }

    /**
     * Executes a conversational query for a given agent.
     *
     * @param agent
     *            The agent being asked.
     * @param question
     *            The question from the user.
     * @param requester
     *            The Player who initiated the query, for receiving error messages.
     */
    public void executeQuery(NerrusAgent agent, String question, Player requester) {
        AgentContext agentContext = new AgentContext(agent);
        String systemPrompt = agentContext.buildQueryPrompt(question, requester);
        ChatRequest request = ChatRequest.builder()
                .model(llmService.getModelName())
                .messages(List.of(
                        ChatMessage.SystemMessage.of(systemPrompt),
                        ChatMessage.UserMessage.of(question)))
                .build();
        llmService.getNextToolCall(request).whenCompleteAsync((chat, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.SEVERE, "LLM call failed in AskDirector.", throwable);
                requester.sendMessage(Component.text("The agent had trouble thinking of a response.", NamedTextColor.RED));
                return;
            }
            String response = chat.firstContent();
            if (response != null && !response.isBlank()) {
                agent.speak(response);
            } else {
                agent.speak("I'm not sure how to answer that.");
            }
        }, plugin.getMainThreadExecutor());
    }
}