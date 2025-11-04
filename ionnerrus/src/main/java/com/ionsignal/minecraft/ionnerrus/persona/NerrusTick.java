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
                    // Check for active debug session for this agent
                    // Using fully qualified class names to avoid import conflicts
                    Optional<DebugSession<AgentDebugState>> sessionOpt = IonCore
                            .getDebugRegistry().getActiveSession(agent.getPersona().getUniqueId(), AgentDebugState.class);
                    if (sessionOpt.isPresent()) {
                        DebugSession<AgentDebugState> session = sessionOpt.get();
                        ExecutionController controller = session.getController().orElse(null);
                        if (controller != null) {
                            // Update state snapshot on main thread (thread-safe)
                            // This captures the current agent state before processing messages
                            AgentDebugState snapshot = AgentDebugState.snapshot(agent);
                            // Store snapshot in session's atomic reference
                            session.setState(snapshot);
                            session.markVisualizationDirty();
                            // Skip message processing if controller is in paused state
                            // This allows the debugger to inspect state between message processing
                            if (controller.isPaused()) {
                                continue; // Skip this agent's message processing for this tick
                            }
                            // Trigger pause before processing next message (non-blocking)
                            // TickBasedController.pause() sets a flag and returns immediately
                            String nextMsg = snapshot.nextMessage() != null ? snapshot.nextMessage() : "No messages";
                            controller.pause("Message Processing", "Next: " + nextMsg);
                            // If pause() returned (TickBasedController never blocks), continue to process
                        }
                    }
                    // Process agent messages (unchanged)
                    agent.processMessages();
                } catch (Exception e) {
                    manager.getLogger().severe("Error processing mailbox for agent " + agent.getName());
                    e.printStackTrace();
                }
            }
        }
    }
}