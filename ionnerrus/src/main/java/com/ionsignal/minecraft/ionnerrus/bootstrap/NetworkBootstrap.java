package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.network.schema.Incoming;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.google.gson.Gson;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class NetworkBootstrap {
    public static final String COLLECTION_PERSONA_MANIFESTS = "persona_manifests";

    private final IonNerrus plugin;
    private final AgentService agentService;
    private final DocumentStore documentStore;
    private final NetworkCommandRegistrar commandRegistrar;
    private final Gson gson;

    public NetworkBootstrap(IonNerrus plugin,
            AgentService agentService,
            DocumentStore documentStore,
            NetworkCommandRegistrar commandRegistrar) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.documentStore = documentStore;
        this.commandRegistrar = commandRegistrar;
        this.gson = new Gson();
    }

    public void registerAll() {
        // Use injected registrar instead of static lookup
        commandRegistrar.registerHandler("SPAWN_AGENT", this::handleSpawnAgent);
        commandRegistrar.registerHandler("DESPAWN_AGENT", this::handleDespawnAgent);
        plugin.getLogger().info("NetworkBootstrap: Listening for SPAWN_AGENT and DESPAWN_AGENT.");
    }

    private CompletableFuture<Incoming.AgentSyncPayload> fetchAgentSyncPayload(UUID definitionId) {
        return documentStore.fetchDocument(COLLECTION_PERSONA_MANIFESTS, definitionId)
                .thenApply(optPayload -> {
                    if (optPayload.isEmpty()) {
                        plugin.getLogger().warning("[DB Fetch] Definition ID " + definitionId + " not found or payload empty.");
                        return null;
                    }
                    String jsonBlob = optPayload.get();
                    // Debug logging to verify JSON structure
                    plugin.getLogger().info("[DB Fetch] JSON for " + definitionId + ": " + jsonBlob);
                    try {
                        return gson.fromJson(jsonBlob, Incoming.AgentSyncPayload.class);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "JSON Parse Error for Definition ID " + definitionId, e);
                        return null;
                    }
                });
    }

    private void handleSpawnAgent(String jsonPayload) {
        // Pure Logic / JSON Parsing (Safe on Async)
        Incoming.SpawnPayload signalPayload;
        try {
            signalPayload = gson.fromJson(jsonPayload, Incoming.SpawnPayload.class);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid JSON in SPAWN_AGENT: " + e.getMessage());
            return;
        }
        plugin.getLogger()
                .info("Network Command: Spawning agent " + signalPayload.name() + " (Definition: " + signalPayload.definitionId() + ")");
        // Database I/O (Keep Async)
        fetchAgentSyncPayload(signalPayload.definitionId()).thenAccept(config -> {
            // Handover to Main Thread (Sync)
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Location spawnLoc = resolveLocation(signalPayload.location());
                    if (spawnLoc == null) {
                        plugin.getLogger().warning("Spawn failed: Invalid location for " + signalPayload.name());
                        return;
                    }
                    if (config != null) {
                        agentService.spawnAgent(signalPayload, config, spawnLoc);
                        plugin.getLogger().info("Spawned " + signalPayload.name() + " with configuration fetched from DB.");
                    } else {
                        plugin.getLogger()
                                .severe("Aborting spawn for " + signalPayload.name() + ": Failed to fetch configuration from DB.");
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
        if (data == null)
            return null;
        World world = null;
        if (data.world() != null) {
            world = Bukkit.getWorld(data.world());
        }
        if (world == null) {
            world = Bukkit.getWorlds().get(0);
        }
        if ("PLAYER".equalsIgnoreCase(data.type())) {
            if (data.playerName() == null)
                return null;
            Player target = Bukkit.getPlayer(data.playerName());
            if (target != null && target.isOnline()) {
                return target.getLocation();
            }
            plugin.getLogger().warning("Network Spawn Failed: Target player '" + data.playerName() + "' not found or offline.");
            return null;
        }
        if ("COORDINATES".equalsIgnoreCase(data.type())) {
            if (data.x() == null || data.y() == null || data.z() == null)
                return null;
            return new Location(world, data.x(), data.y(), data.z());
        }
        return null;
    }
}