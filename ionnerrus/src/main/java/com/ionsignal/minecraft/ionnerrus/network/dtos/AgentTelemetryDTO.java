package com.ionsignal.minecraft.ionnerrus.network.dtos;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import java.util.List;

/**
 * Rich telemetry packet containing the full live state of an agent.
 * Sent periodically (e.g., 1Hz) to update the dashboard.
 */
public record AgentTelemetryDTO(
    String personaId,
    Vitals vitals,
    WorldPosition position,
    Velocity velocity,
    boolean onGround,
    String currentGoal,
    String currentSkill,
    List<String> goalStack,
    String taskDescription
) {
    public record Vitals(double health, int hunger, int oxygen) {}
    public record WorldPosition(String world, double x, double y, double z, float yaw, float pitch) {}
    public record Velocity(double x, double y, double z) {}

    public static AgentTelemetryDTO from(NerrusAgent agent) {
        var persona = agent.getPersona();
        var entity = persona.getEntity(); 
        
        if (entity == null) return null; 

        Location loc = entity.getLocation();
        Vector vel = entity.getVelocity();
        List<String> stack = agent.getGoalStackNames();

        return new AgentTelemetryDTO(
            persona.getUniqueId().toString(),
            new Vitals(
                (entity instanceof org.bukkit.entity.LivingEntity le) ? le.getHealth() : 20.0,
                (entity instanceof org.bukkit.entity.Player p) ? p.getFoodLevel() : 20,
                (entity instanceof org.bukkit.entity.LivingEntity le) ? le.getRemainingAir() : 300
            ),
            new WorldPosition(
                loc.getWorld().getName(), 
                loc.getX(), loc.getY(), loc.getZ(), 
                loc.getYaw(), loc.getPitch()
            ),
            new Velocity(vel.getX(), vel.getY(), vel.getZ()),
            entity.isOnGround(),
            agent.getCurrentGoalName(),
            agent.getCurrentTaskName(),
            stack,
            agent.getActivityDescription()
        );
    }
}