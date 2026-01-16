package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.google.gson.Gson;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.schema.Incoming;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class NetworkBootstrap {
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final DatabaseManager databaseManager;
    private final NetworkCommandRegistrar commandRegistrar;
    private final Gson gson;

    public NetworkBootstrap(IonNerrus plugin, 
                            AgentService agentService, 
                            DatabaseManager databaseManager, 
                            NetworkCommandRegistrar commandRegistrar) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.databaseManager = databaseManager;
        this.commandRegistrar = commandRegistrar;
        this.gson = new Gson();
    }

    public void registerAll() {
        // Use injected registrar instead of static lookup
        commandRegistrar.registerHandler("SPAWN_AGENT", this::handleSpawnAgent);
        commandRegistrar.registerHandler("DESPAWN_AGENT", this::handleDespawnAgent);
        commandRegistrar.registerHandler("COMMAND_REFRESH_CONFIG", this::handleRefreshConfig);
        
        plugin.getLogger().info("NetworkBootstrap: Listening for SPAWN_AGENT, DESPAWN_AGENT, and COMMAND_REFRESH_CONFIG.");
    }

    private CompletableFuture<Incoming.AgentSyncPayload> fetchAgentSyncPayload(UUID definitionId) {
        return CompletableFuture.supplyAsync(() -> {
            String query = "SELECT payload FROM game_entity_sync WHERE id = ?";
            // Use injected databaseManager instead of static lookup
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                
                ps.setObject(1, definitionId);
                
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String jsonBlob = rs.getString("payload");
                        if (jsonBlob == null || jsonBlob.isBlank()) {
                            plugin.getLogger().warning("[DB Fetch] Payload is empty for Definition ID " + definitionId);
                            return null;
                        }
                        
                        // Debug logging to verify JSON structure
                        plugin.getLogger().info("[DB Fetch] JSON for " + definitionId + ": " + jsonBlob);

                        return gson.fromJson(jsonBlob, Incoming.AgentSyncPayload.class);
                    } else {
                        plugin.getLogger().warning("[DB Fetch] Definition ID " + definitionId + " not found in game_entity_sync.");
                        return null;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Database error fetching payload for Definition ID " + definitionId, e);
                return null;
            }
        }, plugin.getOffloadThreadExecutor());
    }

    private void handleRefreshConfig(String jsonPayload) {
        try {
            // 1. Parse JSON (Safe on Async)
            Incoming.RefreshConfigPayload payload = gson.fromJson(jsonPayload, Incoming.RefreshConfigPayload.class);
            UUID definitionId = payload.definitionId();

            plugin.getLogger().info("Network Command: Refreshing config for Definition ID: " + definitionId);

            // 2. Fetch Data (Async) - Do not access AgentService here
            fetchAgentSyncPayload(definitionId).thenAccept(config -> {
                if (config == null) return;

                // 3. Handover to Main Thread (Sync) to find and update the agent
                Bukkit.getScheduler().runTask(plugin, () -> {
                    NerrusAgent agent = agentService.getAgents().stream()
                        .filter(a -> {
                            UUID defId = a.getPersona().getDefinitionId();
                            return defId != null && defId.equals(definitionId);
                        })
                        .findFirst()
                        .orElse(null);

                    if (agent != null) {
                        if (payload.flags().contains("SKIN") && config.skin() != null) {
                            agentService.updateAgentSkin(agent, config.skin());
                        }
                        // Add other flag handlers here (e.g., BEHAVIOR, INVENTORY)
                        plugin.getLogger().info("Refreshed configuration for agent: " + agent.getName());
                    } else {
                        plugin.getLogger().warning("Refresh Failed: No active agent found for Definition ID " + definitionId);
                    }
                });
            });

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to handle COMMAND_REFRESH_CONFIG", e);
        }
    }

    private void handleSpawnAgent(String jsonPayload) {
        // 1. Pure Logic / JSON Parsing (Safe on Async)
        Incoming.SpawnPayload signalPayload;
        try {
            signalPayload = gson.fromJson(jsonPayload, Incoming.SpawnPayload.class);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid JSON in SPAWN_AGENT: " + e.getMessage());
            return;
        }

        plugin.getLogger().info("Network Command: Spawning agent " + signalPayload.name() + " (Definition: " + signalPayload.definitionId() + ")");

        // 2. Database I/O (Keep Async)
        fetchAgentSyncPayload(signalPayload.definitionId()).thenAccept(config -> {
            
            // 3. Handover to Main Thread (Sync)
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    // CRITICAL: Location resolution moved INSIDE the sync task
                    Location spawnLoc = resolveLocation(signalPayload.location());
                    
                    if (spawnLoc == null) {
                        plugin.getLogger().warning("Spawn failed: Invalid location for " + signalPayload.name());
                        return;
                    }

                    if (config != null) {
                        agentService.spawnAgent(signalPayload, config, spawnLoc);
                        plugin.getLogger().info("Spawned " + signalPayload.name() + " with configuration fetched from DB.");
                    } else {
                        plugin.getLogger().severe("Aborting spawn for " + signalPayload.name() + ": Failed to fetch configuration from DB.");
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to execute SPAWN_AGENT", e);
                }
            });
        });
    }

    private void handleDespawnAgent(String jsonPayload) {
        try {
            Incoming.DespawnPayload payload = gson.fromJson(jsonPayload, Incoming.DespawnPayload.class);
            UUID targetId = payload.agentId();
            
            // Dispatch immediately to Main Thread for lookup and removal
            Bukkit.getScheduler().runTask(plugin, () -> {
                var agent = agentService.getAgents().stream()
                    .filter(a -> {
                        UUID defId = a.getPersona().getDefinitionId();
                        return defId != null && defId.equals(targetId);
                    })
                    .findFirst()
                    .orElse(null);

                if (agent != null) {
                    plugin.getLogger().info("Network Command: Despawning agent " + agent.getName());
                    agentService.removeAgent(agent.getName());
                } else {
                    plugin.getLogger().warning("Network Despawn Failed: Agent with Definition ID " + targetId + " not found.");
                }
            });

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to handle DESPAWN_AGENT", e);
        }
    }

    private Location resolveLocation(Incoming.SpawnPayload.SpawnLocation data) {
        if (data == null) return null;
        
        World world = null;
        if (data.world() != null) {
            world = Bukkit.getWorld(data.world());
        }
        if (world == null) {
            world = Bukkit.getWorlds().get(0);
        }

        if ("PLAYER".equalsIgnoreCase(data.type())) {
            if (data.playerName() == null) return null;
            Player target = Bukkit.getPlayer(data.playerName());
            if (target != null && target.isOnline()) {
                return target.getLocation();
            }
            plugin.getLogger().warning("Network Spawn Failed: Target player '" + data.playerName() + "' not found or offline.");
            return null;
        }

        if ("COORDINATES".equalsIgnoreCase(data.type())) {
            if (data.x() == null || data.y() == null || data.z() == null) return null;
            return new Location(world, data.x(), data.y(), data.z());
        }

        return null;
    }
}