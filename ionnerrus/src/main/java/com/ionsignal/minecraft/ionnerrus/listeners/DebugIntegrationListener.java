package com.ionsignal.minecraft.ionnerrus.listeners;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.persona.PersonaHolder;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Listens for Nerrus lifecycle events to clean up IonCore debug sessions.
 * This prevents memory leaks and stuck execution controllers when agents
 * are removed or die while being debugged.
 */
public class DebugIntegrationListener implements Listener {
    private final Logger logger;

    public DebugIntegrationListener(Logger logger) {
        this.logger = logger;
    }

    /**
     * Handles explicit removal via API or Command (e.g., /nerrus remove).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        UUID agentId = event.getAgent().getPersona().getUniqueId();
        cleanupSession(agentId, "Agent Removed");
    }

    /**
     * Handles "natural" death (e.g., killed by zombie, void, etc).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        // Check if the dead entity corresponds to a Persona
        if (event.getEntity() instanceof PersonaHolder holder) {
            UUID agentId = holder.getPersona().getUniqueId();
            cleanupSession(agentId, "Agent Died");
        }
    }

    private void cleanupSession(UUID agentId, String reason) {
        try {
            // IonCore registry handles thread safety and execution controller cancellation
            if (IonCore.getDebugRegistry().hasActiveSession(agentId)) {
                boolean cancelled = IonCore.getDebugRegistry().cancelSession(agentId);
                if (cancelled) {
                    logger.info(String.format("Cancelled active debug session for agent %s (%s).", agentId, reason));
                }
            }
        } catch (Exception e) {
            // Ensure one bad cleanup doesn't break the event chain
            logger.warning("Failed to clean up debug session for " + agentId + ": " + e.getMessage());
        }
    }
}