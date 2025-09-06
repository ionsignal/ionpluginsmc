package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FailObjectiveParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.context.AgentContext;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;

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
import java.util.logging.Level;
import java.util.concurrent.CancellationException;

public class ReActDirector {
    private final IonNerrus plugin;
    private final LLMService llmService;
    private final GoalFactory goalFactory;
    private final GoalRegistry goalRegistry;
    private final List<ChatMessage> conversationHistory;
    private final List<Tool> availableTools;
    private final AgentContext agentContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReActDirector(NerrusAgent agent, GoalRegistry goalRegistry, GoalFactory goalFactory, LLMService llmService) {
        this.plugin = IonNerrus.getInstance();
        this.agentContext = new AgentContext(agent);
        this.availableTools = LLMToolBuilder.fromToolDefinitions(goalRegistry.getAll());
        this.goalRegistry = goalRegistry;
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
            plugin.getLogger().info("--- DRY RUN: Constructed ChatRequest (JSON) ---");
            plugin.getLogger().info(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
        } catch (JsonProcessingException e) {
            plugin.getLogger().log(Level.SEVERE, "Dry run serialization failed", e);
            agent.setBusyWithDirective(false);
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

    private void handleToolCall(ToolCall toolCall, NerrusAgent agent) {
        String toolName = toolCall.getFunction().getName();
        String argumentsJson = toolCall.getFunction().getArguments();
        ToolDefinition toolDef = goalRegistry.get(toolName);
        plugin.getLogger().info("LLM requested tool: " + toolName + " with args: " + argumentsJson);
        if (toolDef == null) {
            reportErrorToLLM("Error: Unknown tool '" + toolName + "'.", toolCall, agent);
            return;
        }
        try {
            Object params = objectMapper.readValue(argumentsJson, toolDef.parametersClass());
            if ("FAIL_OBJECTIVE".equalsIgnoreCase(toolName)) {
                FailObjectiveParameters failParams = (FailObjectiveParameters) params;
                String explanation = failParams.explanation();
                agent.speak("I can't do that. " + explanation);
                plugin.getLogger().warning("ReActDirector terminated by FAIL_OBJECTIVE tool. Type: " + failParams.failureType()
                        + ", Explanation: " + explanation);
                agent.setBusyWithDirective(false);
                return;
            }
            Goal newGoal = goalFactory.createGoal(toolName, params);
            agent.assignGoal(newGoal).whenCompleteAsync((goalResult, throwable) -> {
                // Add specific handling for CancellationException
                if (throwable != null) {
                    if (throwable instanceof CancellationException) {
                        // The goal was cancelled by an external command, like '/nerrus stop'.
                        // This is not a failure, but an intentional stop.
                        plugin.getLogger().info("Directive for agent " + agent.getName() + " was cancelled by user.");
                        agent.speak("Okay, I'll stop what I'm doing.");
                        agent.setBusyWithDirective(false);
                        return; // Terminate the ReAct loop.
                    }
                    // It's a different, unexpected exception. Log it and report failure to the LLM.
                    plugin.getLogger().log(Level.SEVERE, "Goal future completed exceptionally.", throwable);
                    String resultMessage = "FAILURE: An unexpected error occurred in the agent's goal execution: " + throwable.getMessage();
                    ChatMessage toolMessage = ChatMessage.ToolMessage.of(resultMessage, toolCall.getId());
                    conversationHistory.add(toolMessage);
                    cognitiveStep(agent); // Continue the loop, letting the LLM know about the failure.
                    return;
                }
                // This block is now only for normal (non-exceptional) completions.
                String resultMessage = goalResult.status() + ": " + goalResult.message();
                plugin.getLogger().info("Goal '" + toolName + "' finished with result: " + resultMessage);
                ChatMessage toolMessage = ChatMessage.ToolMessage.of(resultMessage, toolCall.getId());
                conversationHistory.add(toolMessage);
                // Trigger the next cognitive step
                cognitiveStep(agent);
            }, plugin.getMainThreadExecutor());
        } catch (JsonProcessingException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to parse tool arguments from LLM.", e);
            reportErrorToLLM("Error: Invalid arguments provided. " + e.getMessage(), toolCall, agent);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create goal: " + e.getMessage());
            reportErrorToLLM("Error: " + e.getMessage(), toolCall, agent);
        }
    }

    private void reportErrorToLLM(String errorMessage, ToolCall toolCall, NerrusAgent agent) {
        ChatMessage toolMessage = ChatMessage.ToolMessage.of(errorMessage, toolCall.getId());
        conversationHistory.add(toolMessage);
        cognitiveStep(agent);
    }
}