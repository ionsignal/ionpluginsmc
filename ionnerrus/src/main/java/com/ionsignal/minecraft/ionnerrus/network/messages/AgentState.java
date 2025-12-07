package com.ionsignal.minecraft.ionnerrus.network.messages;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import org.bukkit.Location;

public record AgentState(
        String id, // Changed from UUID to String for safe JSON serialization
        String name,
        String status, // "ACTIVE", "DESPAWNED"
        LocationData location) {
    public record LocationData(String world, double x, double y, double z) {
    }

    public static AgentState from(NerrusAgent agent) {
        Location loc = agent.getPersona().getLocation();
        return new AgentState(
                agent.getPersona().getUniqueId().toString(), // Convert to String here
                agent.getName(),
                agent.getPersona().isSpawned() ? "ACTIVE" : "DESPAWNED",
                new LocationData(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()));
    }
}