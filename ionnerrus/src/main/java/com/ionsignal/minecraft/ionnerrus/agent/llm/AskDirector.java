package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.llm.context.AgentContext;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.entity.Player;

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
        // Updated to use ChatCompletionCreateParams from openai-java
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(llmService.getModelName())
                // Use Developer message for system prompt (modern OpenAI standard)
                .addMessage(ChatCompletionMessageParam.ofDeveloper(
                        ChatCompletionDeveloperMessageParam.builder()
                                .content(ChatCompletionDeveloperMessageParam.Content.ofText(systemPrompt))
                                .build()))
                .addMessage(ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                                .content(ChatCompletionUserMessageParam.Content.ofText(question))
                                .build()))
                .build();
        llmService.getNextToolCall(params).whenCompleteAsync((completion, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.SEVERE, "LLM call failed in AskDirector.", throwable);
                requester.sendMessage(Component.text("The agent had trouble thinking of a response.", NamedTextColor.RED));
                return;
            }
            // Extract content from the first choice safely
            String response = null;
            if (!completion.choices().isEmpty()) {
                response = completion.choices().get(0).message().content().orElse(null);
            }
            if (response != null && !response.isBlank()) {
                agent.speak(response);
            } else {
                agent.speak("I'm not sure how to answer that.");
            }
        }, plugin.getMainThreadExecutor());
    }
}