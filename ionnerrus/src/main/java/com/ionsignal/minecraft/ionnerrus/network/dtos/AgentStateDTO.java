package com.ionsignal.minecraft.ionnerrus.network.dtos;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import org.bukkit.Location;

import java.util.UUID;

public record AgentStateDTO(
    String id, // Changed from UUID to String for safe JSON serialization
    String name,
    String status, // "ACTIVE", "DESPAWNED"
    LocationData location
) {
    public record LocationData(String world, double x, double y, double z) {}

    public static AgentStateDTO from(NerrusAgent agent) {
        Location loc = agent.getPersona().getLocation();
        return new AgentStateDTO(
            agent.getPersona().getUniqueId().toString(), // Convert to String here
            agent.getName(),
            agent.getPersona().isSpawned() ? "ACTIVE" : "DESPAWNED",
            new LocationData(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ())
        );
    }
}