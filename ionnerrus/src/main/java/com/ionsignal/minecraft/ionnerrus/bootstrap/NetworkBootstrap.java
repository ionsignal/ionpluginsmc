package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.dtos.AgentStateDTO;
import com.ionsignal.minecraft.ionnerrus.network.dtos.DespawnAgentRequest;
import com.ionsignal.minecraft.ionnerrus.network.dtos.SpawnAgentRequest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public class NetworkBootstrap {
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final Gson gson;

    public NetworkBootstrap(IonNerrus plugin, AgentService agentService) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.gson = new Gson();
    }

    public void registerAll() {
        var registrar = IonCore.getInstance().getServiceContainer().getCommandRegistrar();
        registrar.register("SPAWN_AGENT", this::handleSpawn);
        registrar.register("DESPAWN_AGENT", this::handleDespawn);
        registrar.register("COMMAND_SYNC_STATE", this::handleSync);
        
        plugin.getLogger().info("IonCore Network Integration Enabled: Handlers registered.");
        
        // --- FIX: Force immediate sync on startup ---
        // This ensures that if the websocket is already open (IonCore loads first), 
        // we don't wait for a SYNC command that might have already been missed.
        performSync();
    }

    private void performSync() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            int syncedCount = 0;
            for (NerrusAgent agent : agentService.getAgents()) {
                // Only sync spawned agents to avoid confusion
                if (agent.getPersona().isSpawned()) {
                    AgentStateDTO dto = AgentStateDTO.from(agent);
                    IonCore.getInstance().getServiceContainer().broadcast("AGENT_SPAWNED", dto);
                    syncedCount++;
                }
            }
            if (syncedCount > 0) {
                plugin.getLogger().info("[Network] Auto-synced " + syncedCount + " active agents to dashboard.");
            }
        });
    }

    // --- Command Handlers ---

    private CompletableFuture<Object> handleSync(String jsonStr) {
        performSync();
        return CompletableFuture.completedFuture("Sync initiated");
    }

    private CompletableFuture<Object> handleSpawn(String jsonStr) {
        SpawnAgentRequest dto;
        try {
            dto = gson.fromJson(jsonStr, SpawnAgentRequest.class);
        } catch (JsonSyntaxException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid spawn payload: " + e.getMessage()));
        }

        CompletableFuture<Object> result = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                // Idempotency: If agent exists/spawned, just resync UI
                if (agentService.findAgentByName(dto.name()) != null) {
                     NerrusAgent existing = agentService.findAgentByName(dto.name());
                     if (existing.getPersona().isSpawned()) {
                         AgentStateDTO stateDto = AgentStateDTO.from(existing);
                         IonCore.getInstance().getServiceContainer().broadcast("AGENT_SPAWNED", stateDto);
                         result.complete("Agent already active. Synced.");
                         return;
                     }
                }

                Location spawnLoc = resolveLocation(dto.location());
                if (spawnLoc == null) {
                    throw new IllegalArgumentException("Could not resolve valid spawn location.");
                }

                String skinArg = (dto.skinTexture() != null && !dto.skinTexture().isEmpty()) 
                        ? dto.skinTexture() 
                        : dto.name(); 
                
                agentService.spawnAgent(dto.name(), spawnLoc, skinArg);

                plugin.getLogger().info("[Network] Spawned agent: " + dto.name());
                result.complete("Agent " + dto.name() + " spawned successfully.");

            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });

        return result;
    }

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
                    result.complete("Agent not found (already removed).");
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
                return target.getLocation().add(2, 0, 0); 
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
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            return Bukkit.getOnlinePlayers().iterator().next().getLocation().add(2, 0, 0);
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }
}