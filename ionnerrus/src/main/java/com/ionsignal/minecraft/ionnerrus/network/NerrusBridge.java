// src/main/java/com/ionsignal/minecraft/ionnerrus/network/NerrusBridge.java

package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService.AgentSpawnRequest;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentConfig;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatus;
import com.ionsignal.minecraft.ionnerrus.network.model.CoordinateSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.DespawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.IonCommandType;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SkinUpdatePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.TeleportPayload;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Bidirectional bridge between IonCore's PostgreSQL event bus and IonNerrus domain logic.
 *
 * Consolidates NetworkBootstrap (inbound command routing) and NetworkEventListener
 * (outbound event publishing) into a single stored, lifecycle-managed service.
 * Registered as a Bukkit Listener via ListenerRegistrar alongside all other handlers.
 *
 * Inbound: PostgresEventBus → NetworkCommandRegistrar → AgentService
 * Outbound: Bukkit events → PayloadFactory → PostgresEventBus
 */
public class NerrusBridge implements Listener {
    public static final String COLLECTION_PERSONA_MANIFESTS = "persona_manifests";

    private final IonNerrus plugin;
    private final AgentService agentService;
    private final DocumentStore documentStore;
    private final PostgresEventBus eventBus;

    public NerrusBridge(
            IonNerrus plugin,
            AgentService agentService,
            DocumentStore documentStore,
            PostgresEventBus eventBus,
            NetworkCommandRegistrar commandRegistrar) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.documentStore = documentStore;
        this.eventBus = eventBus;
        // Inbound handlers are wired eagerly at construction time.
        // The Bukkit Listener (outbound) is registered separately via ListenerRegistrar.
        registerCommandHandlers(commandRegistrar);
    }

    private void registerCommandHandlers(NetworkCommandRegistrar commandRegistrar) {
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
        plugin.getLogger().info("NerrusBridge: Listening via String-based dispatch.");
    }

    /**
     * Helper to bridge a parsed JSON payload directly to a typed object via then forward it to the
     * handler.
     */
    private <T> void deserializeAndHandle(String json, Class<T> clazz, Consumer<T> handler) {
        try {
            T payload = NerrusObjectMapper.INSTANCE.readValue(json, clazz);
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
                        AgentConfig manifest = NerrusObjectMapper.INSTANCE.readValue(optJson.get(), AgentConfig.class);
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
                        plugin.getLogger().log(Level.SEVERE,
                                "Failed to process spawn manifest for agent " + payload.definitionId(), e);
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
                plugin.getLogger().warning(
                        "Received despawn request for unknown agent definition: " + payload.definitionId());
            }
        });
    }

    /**
     * Resolves the polymorphic SpawnLocation into a concrete Bukkit Location.
     */
    private Location resolveSpawnLocation(SpawnLocation location) {
        if (location == null) {
            plugin.getLogger().severe("Spawn failed: resolveSpawnLocation received null — "
                    + "@JsonTypeInfo deserialization on SpawnLocation may have failed.");
            return null;
        }
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
                plugin.getLogger().warning(
                        "Spawn failed: Target player '" + playerLoc.target().username() + "' is offline.");
                return null;
            }
            return target.getLocation();
        }
        return null;
    }

    @EventHandler
    public void onAgentSpawn(NerrusAgentSpawnEvent event) {
        PayloadFactory.createAgentStateEnvelope(event.getAgent(), AgentStatus.IDLE)
                .ifPresent(eventBus::broadcast);
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        PayloadFactory.createAgentStateEnvelope(event.getAgent(), AgentStatus.OFFLINE)
                .ifPresent(eventBus::broadcast);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        eventBus.broadcast(PayloadFactory.createPlayerJoinEnvelope(event.getPlayer()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        eventBus.broadcast(PayloadFactory.createPlayerQuitEnvelope(event.getPlayer()));
    }
}