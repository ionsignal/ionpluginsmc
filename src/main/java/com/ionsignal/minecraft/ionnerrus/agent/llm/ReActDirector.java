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
import org.bukkit.entity.Player;

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
    private final NerrusAgent agent;
    private volatile boolean isCancelled = false;

    // CHANGE START: Add fields to store the initial directive details for context refreshing.
    private String directive;
    private Player requester;
    // CHANGE END

    public ReActDirector(NerrusAgent agent, GoalRegistry goalRegistry, GoalFactory goalFactory, LLMService llmService) {
        this.plugin = IonNerrus.getInstance();
        this.agent = agent;
        this.agentContext = new AgentContext(agent);
        this.availableTools = LLMToolBuilder.fromToolDefinitions(goalRegistry.getAll(), agent);
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
        this.conversationHistory = new ArrayList<>();
    }

    public void cancel() {
        this.isCancelled = true;
        // This is the key to interrupting the whenComplete block.
        // It triggers the CancellationException handler.
        this.agent.assignGoal(null);
    }

    public void executeDirective(String directive, Player requester) {
        // CHANGE START: Store the directive and requester.
        this.directive = directive;
        this.requester = requester;
        // CHANGE END
        agent.setBusyWithDirective(true);
        String systemPrompt = agentContext.buildSystemPrompt(directive, requester);
        conversationHistory.add(ChatMessage.SystemMessage.of(systemPrompt));
        conversationHistory.add(ChatMessage.UserMessage.of(directive));
        agent.speak("Okay, I'll work on that.");
        // Start the first cognitive step on the main thread.
        Bukkit.getScheduler().runTask(plugin, this::cognitiveStep);
    }

    private void cognitiveStep() {
        if (isCancelled) {
            return;
        }
        // CHANGE START: Refresh the system prompt with the latest agent context before every LLM call.
        String systemPrompt = agentContext.buildSystemPrompt(this.directive, this.requester);
        conversationHistory.set(0, ChatMessage.SystemMessage.of(systemPrompt));
        // CHANGE END
        ChatRequest request = ChatRequest.builder()
                .model(llmService.getModelName())
                .messages(conversationHistory)
                .tools(availableTools)
                .toolChoice(ToolChoiceOption.AUTO)
                .parallelToolCalls(false)
                .build();
        try {
            // Basic logging for development
            plugin.getLogger().info("--- Constructed JSON ---");
            plugin.getLogger().info(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
        } catch (JsonProcessingException e) {
            plugin.getLogger().log(Level.SEVERE, "Dry run serialization failed", e);
            agent.setBusyWithDirective(false);
        }
        llmService.getNextToolCall(request).whenCompleteAsync((chat, throwable) -> {
            if (isCancelled) {
                return;
            }
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
            handleToolCall(toolCalls.get(0));
        }, plugin.getMainThreadExecutor());
    }

    private void handleToolCall(ToolCall toolCall) {
        if (isCancelled) {
            return;
        }
        String toolName = toolCall.getFunction().getName();
        String argumentsJson = toolCall.getFunction().getArguments();
        ToolDefinition toolDef = goalRegistry.get(toolName);
        plugin.getLogger().info("LLM requested tool: " + toolName + " with args: " + argumentsJson);
        if (toolDef == null) {
            reportErrorToLLM("Error: Unknown tool '" + toolName + "'.", toolCall);
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
                if (isCancelled) {
                    return;
                }
                if (throwable != null) {
                    if (throwable instanceof CancellationException) {
                        if (isCancelled) {
                            plugin.getLogger().info("Directive for agent " + agent.getName() + " was cancelled by a new directive.");
                            return;
                        }
                        plugin.getLogger().info("Directive for agent " + agent.getName() + " was cancelled by user.");
                        agent.speak("Okay, I'll stop what I'm doing.");
                        agent.setBusyWithDirective(false);
                        return;
                    }
                    // It's a different, unexpected exception. Log it and report failure to the LLM.
                    plugin.getLogger().log(Level.SEVERE, "Goal future completed exceptionally.", throwable);
                    String failureMemory = String.format("[FAILURE] %s: An unexpected error occurred: %s", toolName.toUpperCase(),
                            throwable.getMessage());
                    agent.recordAction(failureMemory);
                    String resultMessage = "FAILURE: An unexpected error occurred in the agent's goal execution: " + throwable.getMessage();
                    ChatMessage toolMessage = ChatMessage.ToolMessage.of(resultMessage, toolCall.getId());
                    conversationHistory.add(toolMessage);
                    cognitiveStep();
                    return;
                }
                // This block is now only for normal (non-exceptional) completions.
                String resultMessage = goalResult.status() + ": " + goalResult.message();
                String memory = String.format("[%s] %s: %s", goalResult.status(), toolName.toUpperCase(), goalResult.message());
                agent.recordAction(memory);
                plugin.getLogger().info("Goal '" + toolName + "' finished with result: " + resultMessage);
                ChatMessage toolMessage = ChatMessage.ToolMessage.of(resultMessage, toolCall.getId());
                conversationHistory.add(toolMessage);
                cognitiveStep();
            }, plugin.getMainThreadExecutor());
        } catch (JsonProcessingException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to parse tool arguments from LLM.", e);
            reportErrorToLLM("Error: Invalid arguments provided. " + e.getMessage(), toolCall);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create goal: " + e.getMessage());
            reportErrorToLLM("Error: " + e.getMessage(), toolCall);
        }
    }

    private void reportErrorToLLM(String errorMessage, ToolCall toolCall) {
        if (isCancelled) {
            return;
        }
        ChatMessage toolMessage = ChatMessage.ToolMessage.of(errorMessage, toolCall.getId());
        conversationHistory.add(toolMessage);
        cognitiveStep();
    }
}