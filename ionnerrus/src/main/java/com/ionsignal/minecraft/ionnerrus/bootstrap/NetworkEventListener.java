package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NetworkEventListener implements Listener {
    private static final UUID NIL_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @EventHandler
    public void onAgentSpawn(NerrusAgentSpawnEvent event) {
        // Map state to a generic Map to ensure 'ownerId' is included for Node.js schema validation
        Map<String, Object> message = mapToState(event.getAgent(), "ACTIVE");
        // Determine Recipient for Strict Envelope Routing
        UUID ownerId = event.getAgent().getPersona().getOwnerId();
        String recipientId = ownerId != null ? ownerId.toString() : "*";
        // Broadcast with recipientId
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_SPAWNED", recipientId, message);
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        Map<String, Object> message = mapToState(event.getAgent(), "DESPAWNED");
        // Determine Recipient for Strict Envelope Routing
        UUID ownerId = event.getAgent().getPersona().getOwnerId();
        String recipientId = ownerId != null ? ownerId.toString() : "*";
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_REMOVED", recipientId, message);
    }

    /**
     * Maps the agent state to a Map structure compatible with the Node.js AgentStatePayloadSchema.
     * We use a Map instead of the Outgoing.AgentState record here to ensure 'ownerId' is included
     * in the JSON payload, which is required by the backend LifecycleService.
     */
    private Map<String, Object> mapToState(NerrusAgent agent, String status) {
        Location loc = agent.getPersona().getLocation();
        UUID definitionId = agent.getPersona().getDefinitionId();
        if (definitionId == null) {
            definitionId = NIL_UUID;
        }
        Map<String, Object> payload = new HashMap<>();
        // Use String representation of UUIDs for JSON safety
        payload.put("id", agent.getPersona().getUniqueId().toString());
        payload.put("definitionId", definitionId.toString());
        // Include ownerId so the Node.js LifecycleService can route the processed event
        payload.put("ownerId", agent.getPersona().getOwnerId() != null ? agent.getPersona().getOwnerId().toString() : null);
        payload.put("name", agent.getName());
        payload.put("status", status);
        Map<String, Object> locData = new HashMap<>();
        locData.put("world", loc.getWorld().getName());
        locData.put("x", loc.getX());
        locData.put("y", loc.getY());
        locData.put("z", loc.getZ());
        locData.put("yaw", loc.getYaw());
        locData.put("pitch", loc.getPitch());
        payload.put("location", locData);
        return payload;
    }
}