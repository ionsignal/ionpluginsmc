package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.google.gson.Gson;
import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.network.messages.DespawnAgentRequest;
import com.ionsignal.minecraft.ionnerrus.network.messages.SpawnAgentRequest;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

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

    public void registerAll() {
        // 1. Get the Registrar from IonCore
        NetworkCommandRegistrar registrar = IonCore.getInstance()
                .getServiceContainer()
                .getEventBus()
                .getCommandRegistrar();

        // 2. Register Handlers
        registrar.registerHandler("SPAWN_AGENT", this::handleSpawnAgent);
        registrar.registerHandler("DESPAWN_AGENT", this::handleDespawnAgent);

        plugin.getLogger().info("NetworkBootstrap: Listening for SPAWN_AGENT and DESPAWN_AGENT commands.");
    }

    private void handleSpawnAgent(String jsonPayload) {
        // 1. Parse JSON on the Async/IO Thread (Safe & Performant)
        SpawnAgentRequest request;
        try {
            request = gson.fromJson(jsonPayload, SpawnAgentRequest.class);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid JSON in SPAWN_AGENT: " + e.getMessage());
            return;
        }

        // 2. Dispatch Logic to Main Thread (Sync)
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                // Resolve Location (Must be Sync)
                Location spawnLoc = resolveLocation(request.location());
                if (spawnLoc == null) {
                    plugin.getLogger().warning("Network Spawn Failed: Invalid location data for " + request.name());
                    return;
                }

                // Resolve Skin
                SkinData skin = null;
                if (request.skinTexture() != null && !request.skinTexture().isEmpty()) {
                    skin = new SkinData(request.skinTexture(), request.skinSignature());
                }

                // Execute Spawn (Must be Sync)
                plugin.getLogger().info("Network Command: Spawning agent " + request.name());
                agentService.spawnAgent(request.name(), spawnLoc, skin);

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to execute SPAWN_AGENT on main thread", e);
            }
        });
    }

    private void handleDespawnAgent(String jsonPayload) {
        try {
            DespawnAgentRequest request = gson.fromJson(jsonPayload, DespawnAgentRequest.class);
            plugin.getLogger().info("Network Command: Despawning agent " + request.name());
            agentService.removeAgent(request.name());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to handle DESPAWN_AGENT", e);
        }
    }

    private Location resolveLocation(SpawnAgentRequest.SpawnLocationData data) {
        if (data == null) return null;
        
        World world = Bukkit.getWorld(data.world());
        if (world == null) world = Bukkit.getWorlds().get(0); // Fallback

        // Handle "Spawn near Player" logic
        if ("PLAYER".equalsIgnoreCase(data.type()) && data.playerName() != null) {
            Player target = Bukkit.getPlayer(data.playerName());
            if (target != null && target.isOnline()) {
                return target.getLocation();
            }
        }

        // Default to coordinates
        return new Location(world, data.x(), data.y(), data.z());
    }
}