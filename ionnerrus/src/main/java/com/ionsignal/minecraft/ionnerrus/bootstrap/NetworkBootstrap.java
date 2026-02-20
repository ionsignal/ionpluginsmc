package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService.AgentSpawnRequest;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentConfig;
import com.ionsignal.minecraft.ionnerrus.network.model.CoordinateSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.DespawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.IonCommandType;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SkinUpdatePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.TeleportPayload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.logging.Level;

public class NetworkBootstrap {
    public static final String COLLECTION_PERSONA_MANIFESTS = "persona_manifests";

    private final IonNerrus plugin;
    private final AgentService agentService;
    private final DocumentStore documentStore;
    private final NetworkCommandRegistrar commandRegistrar;
    private final ObjectMapper mapper;

    public NetworkBootstrap(IonNerrus plugin,
            AgentService agentService,
            DocumentStore documentStore,
            NetworkCommandRegistrar commandRegistrar) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.documentStore = documentStore;
        this.commandRegistrar = commandRegistrar;
        this.mapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void registerAll() {
        // Spawn
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_SPAWN.getValue(), json -> {
            deserializeAndHandle(json, SpawnPayload.class, this::handleSpawnAgent);
        });
        // Despawn
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_DESPAWN.getValue(), json -> {
            deserializeAndHandle(json, DespawnPayload.class, this::handleDespawnAgent);
        });
        // Teleport
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_TELEPORT.getValue(), json -> {
            deserializeAndHandle(json, TeleportPayload.class, payload -> {
                plugin.getLogger().info("Teleport command received (Not implemented yet)");
            });
        });
        // Skin Update
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_SKIN_UPDATE.getValue(), json -> {
            deserializeAndHandle(json, SkinUpdatePayload.class, payload -> {
                plugin.getLogger().info("Skin update command received (Not implemented yet)");
            });
        });
        plugin.getLogger().info("NetworkBootstrap: Listening for SPAWN_AGENT and DESPAWN_AGENT via String Bridge.");
    }

    /**
     * Helper to bridge Raw JSON String -> Standard Typed Object -> Handler.
     */
    private <T> void deserializeAndHandle(String json, Class<T> clazz, java.util.function.Consumer<T> handler) {
        try {
            T payload = mapper.readValue(json, clazz);
            handler.accept(payload);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to deserialize " + clazz.getSimpleName(), e);
        }
    }

    private void handleSpawnAgent(SpawnPayload payload) {
        if (payload == null)
            return;
        // Validate Owner nesting to prevent NPEs (Risk B mitigation)
        if (payload.owner() == null || payload.owner().identity() == null) {
            plugin.getLogger().warning("Received SpawnPayload with invalid owner structure. Ignoring.");
            return;
        }
        // Fetch Manifest (Async)
        documentStore.fetchDocument(COLLECTION_PERSONA_MANIFESTS, payload.definitionId())
                .thenAccept(optJson -> {
                    if (optJson.isEmpty()) {
                        plugin.getLogger().warning("Cannot spawn agent " + payload.definitionId() + ": Manifest not found.");
                        return;
                    }
                    try {
                        // Use local mapper for Manifest as well to be safe
                        AgentConfig manifest = mapper.readValue(optJson.get(), AgentConfig.class);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Location spawnLoc = resolveSpawnLocation(payload.location());
                            if (spawnLoc == null) {
                                return; // Error logged in resolveSpawnLocation
                            }
                            AgentSpawnRequest request = new AgentSpawnRequest(
                                    manifest.name(),
                                    spawnLoc,
                                    null,
                                    payload.definitionId(),
                                    payload.sessionId(),
                                    payload.owner().identity().uuid());

                            agentService.spawnAgent(request);
                        });
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to process spawn manifest for agent " + payload.definitionId(), e);
                    }
                });
    }

    private void handleDespawnAgent(DespawnPayload payload) {
        if (payload == null)
            return;
        // Switch to Main Thread immediately
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Find agent by Definition ID
            Optional<NerrusAgent> target = agentService.getAgents().stream()
                    .filter(a -> {
                        var p = a.getPersona();
                        return p.getDefinitionId() != null && p.getDefinitionId().equals(payload.definitionId());
                    })
                    .findFirst();
            if (target.isPresent()) {
                agentService.removeAgent(target.get().getName());
            } else {
                plugin.getLogger().warning("Received despawn request for unknown agent definition: " + payload.definitionId());
            }
        });
    }

    /**
     * Resolves the polymorphic SpawnLocation into a concrete Bukkit Location.
     */
    private Location resolveSpawnLocation(SpawnLocation location) {
        if (location instanceof CoordinateSpawnLocation coord) {
            World world = Bukkit.getWorld(coord.world());
            if (world == null) {
                plugin.getLogger().warning("Spawn failed: World '" + coord.world() + "' not loaded.");
                return null;
            }
            return new Location(world, coord.x(), coord.y(), coord.z(), (float) coord.yaw(), (float) coord.pitch());
        } else if (location instanceof PlayerSpawnLocation playerLoc) {
            Player target = Bukkit.getPlayer(playerLoc.target().uuid());
            if (target == null || !target.isOnline()) {
                plugin.getLogger().warning("Spawn failed: Target player '" + playerLoc.target().username() + "' is offline.");
                return null;
            }
            return target.getLocation();
        }
        return null;
    }
}