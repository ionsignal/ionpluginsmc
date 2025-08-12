package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.llm.context.AgentContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.sashirestela.openai.common.tool.Tool;
import io.github.sashirestela.openai.common.tool.ToolCall;
import io.github.sashirestela.openai.common.tool.ToolChoiceOption;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import io.github.sashirestela.openai.domain.chat.ChatRequest;

import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class ReActDirector {
    private final IonNerrus plugin;
    private final LLMService llmService;
    private final GoalFactory goalFactory;
    private final List<ChatMessage> conversationHistory;
    private final List<Tool> availableTools;
    private final AgentContext agentContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReActDirector(NerrusAgent agent, List<Tool> availableTools, GoalFactory goalFactory, LLMService llmService) {
        this.plugin = IonNerrus.getInstance();
        this.agentContext = new AgentContext(agent);
        this.availableTools = availableTools;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
        this.conversationHistory = new ArrayList<>();
    }

    public void executeDirective(String directive, NerrusAgent agent) {
        agent.setBusyWithDirective(true);
        // This is a placeholder. A more robust system would fetch this from a persona config.
        String personaDescription = "You are a helpful and diligent assistant.";
        String systemPrompt = agentContext.buildSystemPrompt(personaDescription, directive);
        conversationHistory.add(ChatMessage.SystemMessage.of(systemPrompt));
        conversationHistory.add(ChatMessage.UserMessage.of(directive));
        agent.speak("Okay, I'll work on that.");
        // Start the first cognitive step on the main thread.
        Bukkit.getScheduler().runTask(plugin, () -> cognitiveStep(agent));
    }

    private void cognitiveStep(NerrusAgent agent) {
        ChatRequest request = ChatRequest.builder()
                .model(llmService.getModelName())
                .messages(conversationHistory)
                .tools(availableTools)
                .toolChoice(ToolChoiceOption.AUTO)
                .parallelToolCalls(false)
                .build();
        // TEMPORARY
        try {
            plugin.getLogger().info("--- DRY RUN: ReActDirector Cognitive Step ---");
            plugin.getLogger()
                    .info("Constructed ChatRequest (JSON): " + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
            agent.speak("I'm thinking about that, but my connection is offline for testing.");
            agent.setBusyWithDirective(false); // Manually unset for the test so the agent isn't stuck
            // return; // Stop before making the actual LLM call
        } catch (JsonProcessingException e) {
            plugin.getLogger().log(Level.SEVERE, "Dry run serialization failed", e);
            agent.setBusyWithDirective(false); // Also unset on failure
        }
        // ..
        llmService.getNextToolCall(request).whenCompleteAsync((chat, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.SEVERE, "LLM call failed in cognitive step.", throwable);
                agent.speak("I'm having trouble thinking right now.");
                agent.setBusyWithDirective(false);
                return;
            }
            ChatMessage.ResponseMessage responseMessage = chat.firstMessage();
            conversationHistory.add(responseMessage);
            List<ToolCall> toolCalls = responseMessage.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                // No tool call, assume the plan is finished or the LLM is just talking.
                String finalMessage = chat.firstContent();
                if (finalMessage != null && !finalMessage.isBlank()) {
                    agent.speak(finalMessage);
                } else {
                    agent.speak("I'm finished with the task.");
                }
                plugin.getLogger().info("ReActDirector finished: LLM provided a final text response.");
                agent.setBusyWithDirective(false);
                return;
            }
            // For now, we only handle the first tool call.
            handleToolCall(toolCalls.get(0), agent);
        }, plugin.getMainThreadExecutor());
    }

    // MODIFIED: This method now uses the CompletableFuture returned by assignGoal.
    private void handleToolCall(ToolCall toolCall, NerrusAgent agent) {
        String toolName = toolCall.getFunction().getName();
        String argumentsJson = toolCall.getFunction().getArguments();
        plugin.getLogger().info("LLM requested tool: " + toolName + " with args: " + argumentsJson);

        // CLEANUP NECESSARY
        if ("CANNOT_COMPLETE".equalsIgnoreCase(toolName)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = objectMapper.readValue(argumentsJson, Map.class);
                String reason = (String) arguments.getOrDefault("reason", "I am unable to complete the objective for an unstated reason.");
                agent.speak("I can't do that. " + reason);
                plugin.getLogger().warning("ReActDirector terminated by CANNOT_COMPLETE tool. Reason: " + reason);
                agent.setBusyWithDirective(false);
                return; // Terminate the cognitive loop
            } catch (JsonProcessingException | ClassCastException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to parse arguments for CANNOT_COMPLETE tool.", e);
                agent.speak("I'm having trouble explaining why I can't do that.");
                agent.setBusyWithDirective(false);
                return;
            }
        }

        try {
            // Assign the goal and attach a callback to the returned future.
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = objectMapper.readValue(argumentsJson, Map.class);
            Goal newGoal = goalFactory.createGoal(toolName, arguments);
            agent.assignGoal(newGoal).whenCompleteAsync((goalResult, throwable) -> {
                String resultMessage;
                if (throwable != null) {
                    plugin.getLogger().log(Level.SEVERE, "Goal future completed exceptionally.", throwable);
                    resultMessage = "FAILURE: An unexpected error occurred in the agent's goal execution.";
                } else {
                    // Use the rich information from the GoalResult object.
                    resultMessage = goalResult.status() + ": " + goalResult.message();
                }
                plugin.getLogger()
                        .info("Goal '" + toolCall.getFunction().getName() + "' finished with result: " + resultMessage);
                // Create and add the tool message to history
                ChatMessage toolMessage = ChatMessage.ToolMessage.of(resultMessage, toolCall.getId());
                conversationHistory.add(toolMessage);
                // Trigger the next cognitive step
                cognitiveStep(agent);
            }, plugin.getMainThreadExecutor());
        } catch (JsonProcessingException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to parse tool arguments from LLM.", e);
            // We need to report this failure back to the LLM.
            ChatMessage toolMessage = ChatMessage.ToolMessage.of("Error: Invalid arguments provided. " + e.getMessage(),
                    toolCall.getId());
            conversationHistory.add(toolMessage);
            cognitiveStep(agent); // Try again
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create goal: " + e.getMessage());
            ChatMessage toolMessage = ChatMessage.ToolMessage.of("Error: " + e.getMessage(), toolCall.getId());
            conversationHistory.add(toolMessage);
            cognitiveStep(agent); // Try again
        }
    }
}