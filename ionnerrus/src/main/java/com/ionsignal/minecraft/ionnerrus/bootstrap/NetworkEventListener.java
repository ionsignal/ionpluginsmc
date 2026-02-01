package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.schema.Outgoing;
import com.ionsignal.minecraft.ionnerrus.network.schema.Shared;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

public class NetworkEventListener implements Listener {

    private static final UUID NIL_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @EventHandler
    public void onAgentSpawn(NerrusAgentSpawnEvent event) {
        Outgoing.AgentState message = mapToState(event.getAgent(), "ACTIVE");
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_SPAWNED", message);
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        Outgoing.AgentState message = mapToState(event.getAgent(), "DESPAWNED");
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_REMOVED", message);
    }

    private Outgoing.AgentState mapToState(NerrusAgent agent, String status) {
        Location loc = agent.getPersona().getLocation();
        UUID definitionId = agent.getPersona().getDefinitionId();
        if (definitionId == null) {
            definitionId = NIL_UUID;
        }
        return new Outgoing.AgentState(
                agent.getPersona().getUniqueId(),
                definitionId,
                agent.getName(),
                status,
                new Shared.LocationData(
                        loc.getWorld().getName(),
                        loc.getX(), loc.getY(), loc.getZ(),
                        loc.getYaw(), loc.getPitch()));
    }
}