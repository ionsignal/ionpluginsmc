package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.messages.AgentState;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class NetworkEventListener implements Listener {

    @EventHandler
    public void onAgentSpawn(NerrusAgentSpawnEvent event) {
        // Convert Domain Object -> DTO
        AgentState dto = AgentState.from(event.getAgent());

        // Broadcast "AGENT_SPAWNED"
        // UPDATED: Use getEventBus().broadcast()
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_SPAWNED", dto);
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        AgentState dto = AgentState.from(event.getAgent());

        // Broadcast "AGENT_REMOVED"
        // UPDATED: Use getEventBus().broadcast()
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_REMOVED", dto);
    }
}