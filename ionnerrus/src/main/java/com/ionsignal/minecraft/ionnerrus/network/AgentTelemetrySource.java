package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.schema.Outgoing;
import com.ionsignal.minecraft.ionnerrus.network.schema.Shared;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Responsible for mapping Agent domain objects to Network Telemetry schemas.
 */
public class AgentTelemetrySource {
    
    private static final UUID NIL_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /**
     * Creates a telemetry payload from a NerrusAgent.
     * 
     * @param agent The agent to map.
     * @return The schema record, or null if the agent is not in a valid state.
     */
    public static @Nullable Outgoing.AgentTelemetry createPayload(NerrusAgent agent) {
        var persona = agent.getPersona();
        var entity = persona.getEntity();

        // Safety check
        if (entity == null || !persona.isSpawned()) {
            return null;
        }

        // Resolve Volatile ID or Fallback to Nil
        UUID definitionId = persona.getDefinitionId();
        if (definitionId == null) {
            definitionId = NIL_UUID;
        }

        Location loc = entity.getLocation();
        Vector vel = entity.getVelocity();
        List<String> stack = agent.getGoalStackNames();

        return new Outgoing.AgentTelemetry(
            definitionId,
            new Outgoing.AgentTelemetry.Vitals(
                (entity instanceof org.bukkit.entity.LivingEntity le) ? le.getHealth() : 20.0,
                (entity instanceof org.bukkit.entity.LivingEntity le) ? le.getMaxHealth() : 20.0,
                (entity instanceof org.bukkit.entity.Player p) ? p.getFoodLevel() : 20,
                (entity instanceof org.bukkit.entity.Player p) ? p.getSaturation() : 5.0f
            ),
            new Shared.LocationData(
                loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch()
            ),
            new Shared.Vector3(vel.getX(), vel.getY(), vel.getZ()),
            agent.getCurrentGoalName(),
            stack
        );
    }
}