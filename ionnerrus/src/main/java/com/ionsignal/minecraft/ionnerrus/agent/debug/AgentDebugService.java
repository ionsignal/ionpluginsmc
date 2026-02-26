package com.ionsignal.minecraft.ionnerrus.agent.debug;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;
import com.ionsignal.minecraft.ioncore.debug.ExecutionControllerFactory;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.PayloadFactory;
import com.ionsignal.minecraft.ionnerrus.network.model.DebugAction;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AgentDebugService {
    private final IonNerrus plugin;
    private final PostgresEventBus eventBus;
    private final PayloadFactory payloadFactory;

    public AgentDebugService(IonNerrus plugin, PostgresEventBus eventBus, PayloadFactory payloadFactory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus cannot be null");
        this.payloadFactory = Objects.requireNonNull(payloadFactory, "payloadFactory cannot be null");
    }

    public CompletableFuture<Void> yieldBeforeThinking(NerrusAgent agent, CognitiveStatePayload payload) {
        var registry = IonCore.getDebugRegistry();
        var sessionId = agent.getPersona().getUniqueId();
        return registry.getActiveSession(sessionId, CognitiveDebugState.class).map(session -> {
            CognitiveDebugState snapshot = new CognitiveDebugState(
                    sessionId,
                    payload.stepCount(),
                    payload.directive(),
                    payload.lastTool(),
                    payload.pendingSummary());
            session.setState(snapshot);
            var envelope = payloadFactory.createDebugStateEnvelope(
                    agent.getPersona().getSessionId(),
                    agent.getOwner(),
                    agent.getName(),
                    payload.stepCount(),
                    payload.directive(),
                    payload.lastTool(),
                    payload.pendingSummary());
            eventBus.broadcast(envelope);
            return session.getController()
                    .map(controller -> controller.pauseAsync("Cognitive Step " + payload.stepCount(), "Awaiting LLM Approval"))
                    .orElse(CompletableFuture.completedFuture(null));
        }).orElse(CompletableFuture.completedFuture(null));
    }

    public void cancelActiveSession(UUID sessionId) {
        var registry = IonCore.getDebugRegistry();
        registry.getActiveSession(sessionId).ifPresent(session -> {
            session.getController().ifPresent(ExecutionController::cancel);
        });
    }

    /**
     * Toggles a debug session for the specified agent.
     *
     * @param agent
     *            The agent to toggle debugging for.
     * @return true if a session was CREATED (debug ON), false if CANCELLED (debug OFF).
     */
    public boolean toggleDebugSession(NerrusAgent agent) {
        UUID sessionId = agent.getPersona().getUniqueId();
        var registry = IonCore.getDebugRegistry();
        if (registry.hasActiveSession(sessionId)) {
            // Turn OFF
            registry.cancelSession(sessionId);
            return false;
        } else {
            // Turn ON: Create initial state with placeholder data to satisfy @NotNull constraints
            CognitiveDebugState initialState = new CognitiveDebugState(
                    sessionId,
                    0,
                    "Attached via command. Awaiting next cognitive cycle...",
                    null,
                    null);
            // Create a non-blocking callback controller using the offload thread executor
            ExecutionController controller = ExecutionControllerFactory.createCallbackBasedNoTimeout(
                    plugin.getOffloadThreadExecutor());
            registry.createSession(sessionId, initialState, controller);
            return true;
        }
    }

    /**
     * Executes a debug action on an active session.
     *
     * @param sessionId
     *            The UUID of the agent/session.
     * @param action
     *            The debug action to perform.
     * @return true if the session was found and action applied, false otherwise.
     */
    public boolean executeDebugAction(UUID sessionId, DebugAction action) {
        var registry = IonCore.getDebugRegistry();
        var sessionOpt = registry.getActiveSession(sessionId);
        if (sessionOpt.isEmpty()) {
            return false;
        }
        sessionOpt.flatMap(com.ionsignal.minecraft.ioncore.debug.DebugSession::getController).ifPresent(controller -> {
            switch (action) {
                case STEP:
                case RESUME:
                    controller.resume();
                    break;
                case CANCEL:
                    // Use registry to safely cancel and clean up the session
                    registry.cancelSession(sessionId);
                    // Explicitly abort the agent's task so CANCEL stops execution entirely
                    NerrusAgent agent = plugin.getAgentService().findAgentBySessionId(sessionId);
                    if (agent != null) {
                        agent.assignGoal(null, null);
                    }
                    break;
                case CONTINUE:
                    controller.continueToEnd();
                    break;
            }
        });
        return true;
    }
}