package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.dtos.AgentStateDTO;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class NetworkEventListener implements Listener {

    @EventHandler
    public void onAgentSpawn(NerrusAgentSpawnEvent event) {
        // Convert Domain Object -> DTO
        AgentStateDTO dto = AgentStateDTO.from(event.getAgent());
        
        // Broadcast "AGENT_SPAWNED"
        // The Core container handles the safety check (is connected?)
        IonCore.getInstance().getServiceContainer().broadcast("AGENT_SPAWNED", dto);
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        AgentStateDTO dto = AgentStateDTO.from(event.getAgent());
        
        // Broadcast "AGENT_REMOVED"
        IonCore.getInstance().getServiceContainer().broadcast("AGENT_REMOVED", dto);
    }
}