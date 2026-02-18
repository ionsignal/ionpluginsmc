package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.PayloadFactory;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatus;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class NetworkEventListener implements Listener {

    private final PostgresEventBus eventBus;

    public NetworkEventListener(PostgresEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @EventHandler
    public void onAgentSpawn(NerrusAgentSpawnEvent event) {
        // Broadcast IDLE state immediately upon spawn
        PayloadFactory.createAgentStateEnvelope(event.getAgent(), AgentStatus.IDLE)
                .ifPresent(eventBus::broadcast);
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        // Broadcast OFFLINE state immediately upon removal
        PayloadFactory.createAgentStateEnvelope(event.getAgent(), AgentStatus.OFFLINE)
                .ifPresent(eventBus::broadcast);
    }
}