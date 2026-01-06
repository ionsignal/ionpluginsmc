package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.messages.AgentGoalEvent;
import com.ionsignal.minecraft.ionnerrus.network.messages.InventoryUpdate;

/**
 * Handles the broadcasting of DISCRETE EVENTS to the IonCore network layer.
 * Continuous telemetry (position, health) is handled by the AgentService loop.
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
        InventoryUpdate record = InventoryUpdate.from(agent);
        // UPDATED: Use getEventBus().broadcast()
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_INVENTORY", record);
    }

    public void broadcastGoalEvent(NerrusAgent agent, String eventType, String goalName, String message) {
        if (!agent.getPersona().isSpawned())
            return;
        AgentGoalEvent event = new AgentGoalEvent(
                agent.getPersona().getUniqueId().toString(),
                eventType,
                goalName,
                message,
                System.currentTimeMillis());
        // UPDATED: Use getEventBus().broadcast()
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_GOAL_EVENT", event);
    }
}