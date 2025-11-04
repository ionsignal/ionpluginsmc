package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.debug.CognitiveDebugState;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
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
import java.util.Optional;
import java.util.logging.Level;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

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

    private String directive;
    private Player requester;
    private int cognitiveStepCount = 0;
    private String lastToolCall = null;
    private String lastToolResult = null;

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

    public String getDirective() {
        return directive;
    }

    public List<ChatMessage> getConversationHistory() {
        return conversationHistory;
    }

    public String getLastToolCall() {
        return lastToolCall;
    }

    public String getLastToolResult() {
        return lastToolResult;
    }

    public int getCognitiveStepCount() {
        return cognitiveStepCount;
    }

    public void cancel() {
        this.isCancelled = true;
        // This is the key to interrupting the whenComplete block.
        // It triggers the CancellationException handler.
        this.agent.assignGoal(null, null);
    }

    public void executeDirective(String directive, Player requester) {
        this.directive = directive;
        this.requester = requester;
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

        // PHASE 4 ADDITION START: Increment step counter for debugging
        cognitiveStepCount++;
        // PHASE 4 ADDITION END

        // PHASE 4 ADDITION START: Check for active debug session and pause if present
        Optional<com.ionsignal.minecraft.ioncore.debug.DebugSession<CognitiveDebugState>> sessionOpt = com.ionsignal.minecraft.ioncore.IonCore
                .getDebugRegistry()
                .getActiveSession(agent.getPersona().getUniqueId(), CognitiveDebugState.class);

        CompletableFuture<Void> pauseFuture = CompletableFuture.completedFuture(null);

        if (sessionOpt.isPresent()) {
            com.ionsignal.minecraft.ioncore.debug.DebugSession<CognitiveDebugState> session = sessionOpt.get();

            // Update state snapshot before pausing
            CognitiveDebugState snapshot = CognitiveDebugState.snapshot(this, agent);
            session.setState(snapshot);
            session.markVisualizationDirty();

            // Get controller and pause if present (returns CompletableFuture)
            pauseFuture = session.getController()
                    .map(controller -> controller.pauseAsync(
                            "Cognitive Step " + cognitiveStepCount,
                            "Preparing LLM request..."))
                    .orElse(CompletableFuture.completedFuture(null));
        }
        // PHASE 4 ADDITION END

        // PHASE 4 CHANGE: Chain pause future before LLM request
        pauseFuture.thenCompose(v -> {
            // Update system prompt (existing logic)
            String systemPrompt = agentContext.buildSystemPrompt(this.directive, this.requester);
            conversationHistory.set(0, ChatMessage.SystemMessage.of(systemPrompt));

            // Build LLM request (existing logic)
            ChatRequest request = ChatRequest.builder()
                    .model(llmService.getModelName())
                    .messages(conversationHistory)
                    .tools(availableTools)
                    .toolChoice(ToolChoiceOption.AUTO)
                    .parallelToolCalls(false)
                    .build();

            // PHASE 4 ADDITION START: Log request for debugging (optional)
            try {
                plugin.getLogger().info("--- LLM Request (Step " + cognitiveStepCount + ") ---");
                plugin.getLogger().info(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
            } catch (JsonProcessingException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to serialize request for logging", e);
            }
            // PHASE 4 ADDITION END

            return llmService.getNextToolCall(request);
        }).whenCompleteAsync((chat, throwable) -> {
            if (isCancelled) {
                return;
            }

            if (throwable != null) {
                plugin.getLogger().log(Level.SEVERE, "LLM call failed in cognitive step.", throwable);
                agent.speak("I'm having trouble thinking right now.");
                agent.setBusyWithDirective(false);
                return;
            }

            // Process LLM response (existing logic)
            ChatMessage.ResponseMessage responseMessage = chat.firstMessage();
            conversationHistory.add(responseMessage);
            List<ToolCall> toolCalls = responseMessage.getToolCalls();

            if (toolCalls == null || toolCalls.isEmpty()) {
                // No tool call, finish directive
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

            // PHASE 4 ADDITION START: Track last tool call for debugging
            ToolCall toolCall = toolCalls.get(0);
            this.lastToolCall = toolCall.getFunction().getName();
            this.lastToolResult = null; // Will be updated in handleToolCall
            // PHASE 4 ADDITION END

            handleToolCall(toolCall);
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
            agent.assignGoal(newGoal, params).whenCompleteAsync((goalResult, throwable) -> {
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
                    this.lastToolResult = resultMessage;
                    cognitiveStep();
                    return;
                }
                String status = goalResult instanceof GoalResult.Success ? "SUCCESS" : "FAILURE";
                String resultMessage = status + ": " + goalResult.message();
                String memory = String.format("[%s] %s: %s", status, toolName.toUpperCase(), goalResult.message());
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
        this.lastToolResult = errorMessage;
        cognitiveStep();
    }
}