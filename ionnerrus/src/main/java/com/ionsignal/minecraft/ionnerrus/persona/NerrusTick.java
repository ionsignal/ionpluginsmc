// File: ionnerrus/src/main/java/com/ionsignal/minecraft/ionnerrus/persona/NerrusTick.java
package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;

public class NerrusTick extends BukkitRunnable {
    private final NerrusManager manager;

    public NerrusTick(NerrusManager manager) {
        this.manager = manager;
    }

    @Override
    public void run() {
        // 1. Tick Physical Personas (Physics/Movement)
        for (Persona persona : manager.getRegistry().getAll()) {
            if (persona.isSpawned()) {
                if (isPaused(persona)) {
                    continue; 
                }
                try {
                    persona.tick();
                } catch (Exception e) {
                    manager.getLogger().severe("Error ticking persona " + persona.getName());
                    e.printStackTrace();
                }
            }
        }

        // 2. Tick Agents (Brain/Network)
        IonNerrus plugin = IonNerrus.getInstance();
        if (plugin != null && plugin.getAgentService() != null) {
            for (NerrusAgent agent : plugin.getAgentService().getAgents()) {
                try {
                    // Debug Session Logic
                    Optional<DebugSession<AgentDebugState>> sessionOpt = IonCore
                            .getDebugRegistry()
                            .getActiveSession(agent.getPersona().getUniqueId(), AgentDebugState.class);

                    if (sessionOpt.isPresent()) {
                        DebugSession<AgentDebugState> session = sessionOpt.get();
                        ExecutionController controller = session.getController().orElse(null);
                        
                        if (controller != null && controller.isPaused()) {
                            continue; // Skip if paused by debug tool
                        }
                        
                        // Update Debug Snapshot
                        AgentDebugState snapshot = AgentDebugState.snapshot(agent);
                        session.setState(snapshot);
                        
                        if (controller != null) {
                            String nextMsg = snapshot.nextMessage() != null ? snapshot.nextMessage() : "No messages";
                            controller.pause("Message Processing", "Next: " + nextMsg);
                        }
                    }

                    // *** THE FIX: Call tick(), NOT processMessages() ***
                    agent.tick(); 

                } catch (Exception e) {
                    manager.getLogger().severe("Error ticking agent " + agent.getName());
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean isPaused(Persona persona) {
        Optional<DebugSession<AgentDebugState>> sessionOpt = IonCore.getDebugRegistry()
                .getActiveSession(persona.getUniqueId(), AgentDebugState.class);
        return sessionOpt
                .flatMap(DebugSession::getController)
                .map(ExecutionController::isPaused)
                .orElse(false);
    }
}