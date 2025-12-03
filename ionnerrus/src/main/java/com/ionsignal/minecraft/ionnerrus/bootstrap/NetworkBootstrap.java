package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.network.dtos.DespawnAgentRequest;
import com.ionsignal.minecraft.ionnerrus.network.dtos.SpawnAgentRequest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class NetworkBootstrap {
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final Gson gson;

    public NetworkBootstrap(IonNerrus plugin, AgentService agentService) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.gson = new Gson();
    }

    /**
     * Registers Nerrus-specific command handlers with the IonCore switchboard.
     */
    public void registerAll() {
        var registrar = IonCore.getInstance().getServiceContainer().getCommandRegistrar();
        registrar.register("SPAWN_AGENT", this::handleSpawn);
        registrar.register("DESPAWN_AGENT", this::handleDespawn);
        plugin.getLogger().info("IonCore Network Integration Enabled: Handlers registered.");
    }

    // --- Command Handlers ---

    private CompletableFuture<Object> handleSpawn(String jsonStr) {
        // 1. Parse String to DTO using Nerrus's Gson
        SpawnAgentRequest dto;
        try {
            dto = gson.fromJson(jsonStr, SpawnAgentRequest.class);
        } catch (JsonSyntaxException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid spawn payload format: " + e.getMessage()));
        }

        CompletableFuture<Object> result = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Location spawnLoc = resolveLocation(dto.location());
                if (spawnLoc == null) {
                    throw new IllegalArgumentException("Could not resolve valid spawn location.");
                }

                if (agentService.findAgentByName(dto.name()) != null) {
                    throw new IllegalStateException("Agent '" + dto.name() + "' already exists.");
                }

                String skinArg = (dto.skinTexture() != null && !dto.skinTexture().isEmpty()) 
                        ? dto.skinTexture() 
                        : dto.name(); 
                
                agentService.spawnAgent(dto.name(), spawnLoc, skinArg);

                plugin.getLogger().info("[Network] Spawned agent: " + dto.name());
                result.complete("Agent " + dto.name() + " spawned successfully.");

            } catch (Exception e) {
                // Ensure the future fails so the web client gets the error
                result.completeExceptionally(e);
            }
        });

        return result;
    }

    // UPDATE: Argument is now String jsonStr
    private CompletableFuture<Object> handleDespawn(String jsonStr) {
        DespawnAgentRequest dto;
        try {
            dto = gson.fromJson(jsonStr, DespawnAgentRequest.class);
        } catch (JsonSyntaxException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid despawn payload"));
        }

        CompletableFuture<Object> result = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                boolean removed = agentService.removeAgent(dto.name());
                if (removed) {
                    result.complete("Agent " + dto.name() + " removed.");
                } else {
                    result.completeExceptionally(new IllegalArgumentException("Agent not found: " + dto.name()));
                }
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });

        return result;
    }

    // --- Helpers ---

    private Location resolveLocation(SpawnAgentRequest.SpawnLocationData data) {
        if (data == null) return getDefaultSpawn();

        if ("PLAYER".equalsIgnoreCase(data.type()) && data.playerName() != null) {
            Player target = Bukkit.getPlayer(data.playerName());
            if (target != null && target.isOnline()) {
                return target.getLocation().add(2, 0, 0); // Spawn near player
            }
        } else if ("COORDINATES".equalsIgnoreCase(data.type()) && data.world() != null) {
            World world = Bukkit.getWorld(data.world());
            if (world != null) {
                return new Location(world, data.x(), data.y(), data.z());
            }
        }
        
        return getDefaultSpawn();
    }

    private Location getDefaultSpawn() {
        // Simple fallback to first world spawn or online player
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            return Bukkit.getOnlinePlayers().iterator().next().getLocation().add(2, 0, 0);
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }
}