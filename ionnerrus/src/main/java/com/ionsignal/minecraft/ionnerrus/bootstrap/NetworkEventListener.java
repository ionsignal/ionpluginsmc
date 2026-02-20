package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.PayloadFactory;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatus;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class NetworkEventListener implements Listener {

    private final PostgresEventBus eventBus;

    public NetworkEventListener(PostgresEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @EventHandler
    public void onAgentSpawn(NerrusAgentSpawnEvent event) {
        PayloadFactory.createAgentStateEnvelope(event.getAgent(), AgentStatus.IDLE)
                .ifPresent(eventBus::broadcast);
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        PayloadFactory.createAgentStateEnvelope(event.getAgent(), AgentStatus.OFFLINE)
                .ifPresent(eventBus::broadcast);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        eventBus.broadcast(PayloadFactory.createPlayerJoinEnvelope(event.getPlayer()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        eventBus.broadcast(PayloadFactory.createPlayerQuitEnvelope(event.getPlayer()));
    }
}
