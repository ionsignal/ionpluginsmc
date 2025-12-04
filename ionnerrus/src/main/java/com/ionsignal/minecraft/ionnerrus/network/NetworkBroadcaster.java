package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.dtos.AgentTelemetryDTO;
import com.ionsignal.minecraft.ionnerrus.network.dtos.InventoryUpdateDTO;
import com.ionsignal.minecraft.ionnerrus.network.dtos.AgentGoalEventDTO;


/**
 * Handles the broadcasting of agent state to the IonCore network layer.
 * Ensures data capture happens on the Main Thread and serialization happens Async.
 */
public class NetworkBroadcaster {
    private final IonNerrus plugin;

    public NetworkBroadcaster(IonNerrus plugin) {
        this.plugin = plugin;
    }

    public void broadcastTelemetry(NerrusAgent agent) {
        if (!agent.getPersona().isSpawned()) return;

        // 1. Capture State (Must be on Main Thread)
        AgentTelemetryDTO dto = AgentTelemetryDTO.from(agent);
        if (dto == null) return;

        // 2. Broadcast (Async Handled by IonCore container)
        IonCore.getInstance().getServiceContainer().broadcast("AGENT_TELEMETRY", dto);
    }

    public void broadcastInventory(NerrusAgent agent) {
        if (!agent.getPersona().isSpawned()) return;
        InventoryUpdateDTO dto = InventoryUpdateDTO.from(agent);
        IonCore.getInstance().getServiceContainer().broadcast("AGENT_INVENTORY", dto);
    }
    
    public void broadcastGoalEvent(NerrusAgent agent, String eventType, String goalName, String message) {
        if (!agent.getPersona().isSpawned()) return;

        AgentGoalEventDTO dto = new AgentGoalEventDTO(
            agent.getPersona().getUniqueId().toString(),
            eventType,
            goalName,
            message,
            System.currentTimeMillis()
        );
        
        IonCore.getInstance().getServiceContainer().broadcast("AGENT_GOAL_EVENT", dto);
    }
}