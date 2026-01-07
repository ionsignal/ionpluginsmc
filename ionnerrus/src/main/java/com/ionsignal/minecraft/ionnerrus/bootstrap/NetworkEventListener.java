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
        AgentState message = AgentState.from(event.getAgent());
        // Broadcast "AGENT_SPAWNED"
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_SPAWNED", message);
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        AgentState message = AgentState.from(event.getAgent());
        // Broadcast "AGENT_REMOVED"
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_REMOVED", message);
    }
}