package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.dtos.InventoryUpdateDTO;
import com.ionsignal.minecraft.ionnerrus.network.dtos.AgentGoalEventDTO;

/**
 * Handles the broadcasting of DISCRETE EVENTS to the IonCore network layer.
 * Continuous telemetry (position, health) is now handled by AgentTelemetrySource.
 */
public class NetworkBroadcaster {
    @SuppressWarnings("unused")
    private final IonNerrus plugin;

    public NetworkBroadcaster(IonNerrus plugin) {
        this.plugin = plugin;
    }

    public void broadcastInventory(NerrusAgent agent) {
        if (!agent.getPersona().isSpawned())
            return;
        InventoryUpdateDTO dto = InventoryUpdateDTO.from(agent);
        IonCore.getInstance().getServiceContainer().broadcast("AGENT_INVENTORY", dto);
    }

    public void broadcastGoalEvent(NerrusAgent agent, String eventType, String goalName, String message) {
        if (!agent.getPersona().isSpawned())
            return;
        AgentGoalEventDTO dto = new AgentGoalEventDTO(
                agent.getPersona().getUniqueId().toString(),
                eventType,
                goalName,
                message,
                System.currentTimeMillis());
        IonCore.getInstance().getServiceContainer().broadcast("AGENT_GOAL_EVENT", dto);
    }
}