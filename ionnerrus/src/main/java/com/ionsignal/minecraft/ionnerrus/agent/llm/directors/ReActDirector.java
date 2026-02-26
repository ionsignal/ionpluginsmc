package com.ionsignal.minecraft.ionnerrus.agent.llm.directors;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.debug.CognitiveDebugState;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalResult;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FailObjectiveParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tools.ToolSchemaFactory;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.agent.llm.prompts.PromptContext;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tools.ToolDefinition;
import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

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

    private final List<ChatCompletionMessageParam> conversationHistory;
    private final List<ChatCompletionTool> availableTools;

    private final PromptContext agentContext;
    private final NerrusAgent agent;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private volatile boolean isCancelled = false;

    private String directive;
    private Player requester;
    private int cognitiveStepCount = 0;
    private String lastToolCall = null;
    private String lastToolResult = null;

    public ReActDirector(NerrusAgent agent, GoalRegistry goalRegistry, GoalFactory goalFactory, LLMService llmService) {
        this.plugin = IonNerrus.getInstance();
        this.agent = agent;
        this.agentContext = new PromptContext(agent);
        this.availableTools = ToolSchemaFactory.fromToolDefinitions(goalRegistry.getAll(), agent);
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
        this.conversationHistory = new ArrayList<>();
    }

    public String getDirective() {
        return directive;
    }

    public List<ChatCompletionMessageParam> getConversationHistory() {
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
        // Ensure any pending debug pause is cancelled
        try {
            IonCore.getDebugRegistry()
                    .getActiveSession(agent.getPersona().getUniqueId())
                    .flatMap(DebugSession::getController)
                    .ifPresent(ExecutionController::cancel);
        } catch (Exception ignored) {
        }
        this.agent.assignGoal(null, null);
    }

    public void executeDirective(String directive, Player requester) {
        this.directive = directive;
        this.requester = requester;
        agent.setBusyWithDirective(true);
        String systemPrompt = agentContext.buildSystemPrompt(directive, requester);
        conversationHistory.add(ChatCompletionMessageParam.ofDeveloper(
                ChatCompletionDeveloperMessageParam.builder()
                        .content(ChatCompletionDeveloperMessageParam.Content.ofText(systemPrompt))
                        .build()));
        conversationHistory.add(ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                        .content(ChatCompletionUserMessageParam.Content.ofText(directive))
                        .build()));
        agent.speak("Okay, I'll work on that.");
        Bukkit.getScheduler().runTask(plugin, this::cognitiveStep);
    }

    private void cognitiveStep() {
        if (isCancelled) {
            return;
        }
        cognitiveStepCount++;
        // Context Gathering & Prompt Construction (Main Thread)
        // We do this synchronously to ensure thread safety with Bukkit API
        String systemPrompt = agentContext.buildSystemPrompt(this.directive, this.requester);
        conversationHistory.set(0, ChatCompletionMessageParam.ofDeveloper(
                ChatCompletionDeveloperMessageParam.builder()
                        .content(ChatCompletionDeveloperMessageParam.Content.ofText(systemPrompt))
                        .build()));
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(llmService.getModelName())
                .messages(conversationHistory)
                .tools(availableTools)
                .toolChoice(ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.AUTO))
                .parallelToolCalls(false)
                .build();
        // Debug Interception (Main Thread)
        CompletableFuture<Void> pauseFuture = CompletableFuture.completedFuture(null);
        try {
            Optional<DebugSession<CognitiveDebugState>> sessionOpt = IonCore.getDebugRegistry()
                    .getActiveSession(agent.getPersona().getUniqueId(), CognitiveDebugState.class);
            if (sessionOpt.isPresent()) {
                DebugSession<CognitiveDebugState> session = sessionOpt.get();
                // Create snapshot with the pending request summary
                String requestSummary = "Sending " + params.messages().size() + " messages. Tools: " + availableTools.size();
                CognitiveDebugState snapshot = CognitiveDebugState.snapshot(this, agent, requestSummary);
                session.setState(snapshot);
                session.markVisualizationDirty();
                pauseFuture = session.getController()
                        .map(controller -> controller.pauseAsync(
                                "Cognitive Step " + cognitiveStepCount,
                                "Preparing LLM request..."))
                        .orElse(CompletableFuture.completedFuture(null));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error during debug session check", e);
        }
        // Execution Chain
        pauseFuture.thenComposeAsync(v -> {
            // Async Boundary: Offload Thread
            // Check cancellation immediately after pause resumes
            if (isCancelled) {
                return CompletableFuture.failedFuture(new CancellationException("Director cancelled"));
            }
            try {
                plugin.getLogger().info("--- LLM Request (Step " + cognitiveStepCount + ") ---");
                // Use the params constructed on the main thread
                return llmService.getNextToolCall(params);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }, plugin.getOffloadThreadExecutor()).whenCompleteAsync((completion, throwable) -> {
            // Response Handling: Main Thread
            if (isCancelled) {
                return;
            }
            if (throwable != null) {
                if (throwable instanceof CancellationException) {
                    return;
                }
                plugin.getLogger().log(Level.SEVERE, "LLM call failed in cognitive step.", throwable);
                agent.speak("I'm having trouble thinking right now.");
                agent.setBusyWithDirective(false);
                return;
            }
            // Process ChatCompletion response
            if (completion.choices().isEmpty()) {
                plugin.getLogger().warning("LLM returned no choices.");
                return;
            }
            ChatCompletion.Choice choice = completion.choices().get(0);
            // Refusal-First Logic:
            // Check if model refused the request (Safety/Policy)
            if (choice.message().refusal().isPresent()) {
                String refusal = choice.message().refusal().get();
                plugin.getLogger().warning("LLM Refusal: " + refusal);
                agent.speak("I can't do that. " + refusal);
                agent.setBusyWithDirective(false);
                return;
            }
            // Check for tool calls
            if (choice.message().toolCalls().isPresent() && !choice.message().toolCalls().get().isEmpty()) {
                List<ChatCompletionMessageToolCall> toolCalls = choice.message().toolCalls().get();
                ChatCompletionMessageToolCall toolCallToExecute;
                // History Sanitization: Handle parallel calls if model ignores parallelToolCalls(false)
                if (toolCalls.size() > 1) {
                    plugin.getLogger().warning("Sanitizing parallel tool calls. Dropped " + (toolCalls.size() - 1) + " calls.");
                    toolCallToExecute = toolCalls.get(0);
                    // Create synthetic message containing ONLY the first tool call
                    ChatCompletionAssistantMessageParam syntheticMsg = ChatCompletionAssistantMessageParam.builder()
                            .content(choice.message().content().orElse("")) // Safe optional access
                            .toolCalls(List.of(toolCallToExecute))
                            .build();
                    conversationHistory.add(ChatCompletionMessageParam.ofAssistant(syntheticMsg));
                } else {
                    // Standard path: 1 tool call
                    toolCallToExecute = toolCalls.get(0);
                    conversationHistory.add(ChatCompletionMessageParam.ofAssistant(choice.message().toParam()));
                }
                if (toolCallToExecute.function().isPresent()) {
                    this.lastToolCall = toolCallToExecute.function().get().function().name();
                }
                this.lastToolResult = null;
                handleToolCall(toolCallToExecute);
            } else {
                // No tool call, check for content
                // Add original message to history
                conversationHistory.add(ChatCompletionMessageParam.ofAssistant(choice.message().toParam()));
                String finalMessage = choice.message().content().orElse("I'm finished with the task.");
                agent.speak(finalMessage);
                plugin.getLogger().info("ReActDirector finished: LLM provided a final text response.");
                agent.setBusyWithDirective(false);
            }
        }, plugin.getMainThreadExecutor());
    }

    private void handleToolCall(ChatCompletionMessageToolCall toolCall) {
        if (isCancelled) {
            return;
        }
        // Extract function details
        if (!toolCall.function().isPresent()) {
            plugin.getLogger().warning("Received tool call without function data.");
            return;
        }
        ChatCompletionMessageFunctionToolCall functionTool = toolCall.function().get();
        String toolName = functionTool.function().name();
        String argumentsJson = functionTool.function().arguments();
        String callId = functionTool.id();
        ToolDefinition toolDef = goalRegistry.get(toolName);
        plugin.getLogger().info("LLM requested tool: " + toolName + " with args: " + argumentsJson);
        if (toolDef == null) {
            reportErrorToLLM("Error: Unknown tool '" + toolName + "'.", callId);
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
                    plugin.getLogger().log(Level.SEVERE, "Goal future completed exceptionally.", throwable);
                    String failureMemory = String.format("[FAILURE] %s: An unexpected error occurred: %s", toolName.toUpperCase(),
                            throwable.getMessage());
                    agent.recordAction(failureMemory);
                    String resultMessage = "FAILURE: An unexpected error occurred in the agent's goal execution: " + throwable.getMessage();
                    addToolResultMessage(resultMessage, callId);
                    this.lastToolResult = resultMessage;
                    cognitiveStep();
                    return;
                }
                String status = goalResult instanceof GoalResult.Success ? "SUCCESS" : "FAILURE";
                String resultMessage = status + ": " + goalResult.message();
                String memory = String.format("[%s] %s: %s", status, toolName.toUpperCase(), goalResult.message());
                agent.recordAction(memory);
                plugin.getLogger().info("Goal '" + toolName + "' finished with result: " + resultMessage);
                addToolResultMessage(resultMessage, callId);
                cognitiveStep();
            }, plugin.getMainThreadExecutor());
        } catch (JsonProcessingException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to parse tool arguments from LLM.", e);
            reportErrorToLLM("Error: Invalid arguments provided. " + e.getMessage(), callId);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create goal: " + e.getMessage());
            reportErrorToLLM("Error: " + e.getMessage(), callId);
        }
    }

    private void reportErrorToLLM(String errorMessage, String toolCallId) {
        if (isCancelled) {
            return;
        }
        addToolResultMessage(errorMessage, toolCallId);
        this.lastToolResult = errorMessage;
        cognitiveStep();
    }

    private void addToolResultMessage(String content, String toolCallId) {
        conversationHistory.add(ChatCompletionMessageParam.ofTool(
                ChatCompletionToolMessageParam.builder()
                        .content(ChatCompletionToolMessageParam.Content.ofText(content))
                        .toolCallId(toolCallId)
                        .build()));
    }
}