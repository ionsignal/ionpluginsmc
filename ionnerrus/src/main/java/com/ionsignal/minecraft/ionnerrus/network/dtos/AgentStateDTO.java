package com.ionsignal.minecraft.ionnerrus.network.dtos;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import org.bukkit.Location;

import java.util.UUID;

/**
 * Snapshot of an agent's state sent to the web dashboard.
 */
public record AgentStateDTO(
    UUID id,
    String name,
    String status, // "IDLE", "WORKING", "OFFLINE"
    LocationData location
) {
    public record LocationData(String world, double x, double y, double z) {}

    /**
     * Factory method to create a DTO from a live agent.
     */
    public static AgentStateDTO from(NerrusAgent agent) {
        Location loc = agent.getPersona().getLocation();
        return new AgentStateDTO(
            agent.getPersona().getUniqueId(),
            agent.getName(),
            agent.getPersona().isSpawned() ? "ACTIVE" : "DESPAWNED",
            new LocationData(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ())
        );
    }
}