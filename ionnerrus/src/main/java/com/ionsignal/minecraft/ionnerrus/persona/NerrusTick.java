package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState;

import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;

/**
 * A BukkitRunnable responsible for ticking all active Personas on the main server thread, enhanced
 * with `ioncore` debug session integration for agent message processing.
 */
public class NerrusTick extends BukkitRunnable {
    private final NerrusManager manager;

    public NerrusTick(NerrusManager manager) {
        this.manager = manager;
    }

    @Override
    public void run() {
        // Original persona ticking
        for (Persona persona : manager.getRegistry().getAll()) {
            if (persona.isSpawned()) {
                try {
                    persona.tick();
                } catch (Exception e) {
                    manager.getLogger().severe("Error ticking persona " + persona.getName() + " (" + persona.getUniqueId() + ")");
                    e.printStackTrace();
                }
            }
        }
        // Process agent messages to drive the message processing loop
        IonNerrus plugin = IonNerrus.getInstance();
        if (plugin != null && plugin.getAgentService() != null) {
            for (NerrusAgent agent : plugin.getAgentService().getAgents()) {
                try {
                    // Unified debug session integration check for active debug session for this agent
                    Optional<DebugSession<com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState>> sessionOpt = IonCore
                            .getDebugRegistry()
                            .getActiveSession(
                                    agent.getPersona().getUniqueId(),
                                    com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState.class);

                    if (sessionOpt.isPresent()) {
                        DebugSession<com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState> session = sessionOpt.get();
                        ExecutionController controller = session.getController().orElse(null);
                        // Complete visualization flow skips agent message processing if controller is paused to allow for
                        // inspection between ticks
                        if (controller != null && controller.isPaused()) {
                            continue;
                        }
                        // Update state snapshot and mark visualization dirty which captures the current agent state before
                        // processing next message
                        com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState snapshot = com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState
                                .snapshot(agent);
                        session.setState(snapshot); // Atomically marks visualization dirty
                        // Signal pause point to controller (non-blocking for TickBasedController)
                        if (controller != null) {
                            String nextMsg = snapshot.nextMessage() != null ? snapshot.nextMessage() : "No messages";
                            controller.pause("Message Processing", "Next: " + nextMsg);
                        }
                    }
                    // Process agent messages (will be skipped if controller is paused)
                    agent.processMessages();
                } catch (Exception e) {
                    manager.getLogger().severe("Error processing mailbox for agent " + agent.getName());
                    e.printStackTrace();
                }
            }
        }
    }
}