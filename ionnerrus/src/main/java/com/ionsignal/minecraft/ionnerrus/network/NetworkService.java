package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.commands.SpawnAgentCommand;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentConfig;
import com.ionsignal.minecraft.ionnerrus.network.model.SessionStatus;
import com.ionsignal.minecraft.ionnerrus.network.model.CoordinateSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.CommandFailedPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.DespawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.IonCommandType;
import com.ionsignal.minecraft.ionnerrus.network.model.PersonaListResponsePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.Skin;
import com.ionsignal.minecraft.ionnerrus.network.model.SkinUpdatePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.SystemPersonaKillPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.TeleportPayload;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;
import java.util.logging.Level;

public class NetworkService implements Listener {
    public static final String COLLECTION_PERSONA_MANIFESTS = "persona_manifests";

    private final IonNerrus plugin;
    private final AgentService agentService;
    private final DocumentStore documentStore;
    private final PostgresEventBus eventBus;

    public NetworkService(
            IonNerrus plugin,
            AgentService agentService,
            DocumentStore documentStore,
            PostgresEventBus eventBus,
            NetworkCommandRegistrar commandRegistrar) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.documentStore = documentStore;
        this.eventBus = eventBus;
        registerCommandHandlers(commandRegistrar);
    }

    private void registerCommandHandlers(NetworkCommandRegistrar commandRegistrar) {
        commandRegistrar.registerHandler(IonCommandType.SYSTEM_COMMAND_FAILED.getValue(), node -> {
            deserializeAndHandle(node, CommandFailedPayload.class, this::handleCommandFailed);
        });
        commandRegistrar.registerHandler(IonCommandType.SYSTEM_PERSONA_KILL.getValue(), node -> {
            deserializeAndHandle(node, SystemPersonaKillPayload.class, this::handleSystemKill);
        });
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_SPAWN.getValue(), node -> {
            deserializeAndHandle(node, SpawnPayload.class, this::handleSpawnAgent);
        });
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_DESPAWN.getValue(), node -> {
            deserializeAndHandle(node, DespawnPayload.class, this::handleDespawnAgent);
        });
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_TELEPORT.getValue(), node -> {
            deserializeAndHandle(node, TeleportPayload.class, payload -> {
                plugin.getLogger().info("Teleport command received (Not implemented yet)");
            });
        });
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_SKIN_UPDATE.getValue(), node -> {
            deserializeAndHandle(node, SkinUpdatePayload.class, payload -> {
                plugin.getLogger().info("Skin update command received (Not implemented yet)");
            });
        });
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_LIST.getValue(), node -> {
            deserializeAndHandle(node, PersonaListResponsePayload.class, this::handlePersonaListResponse);
        });
        plugin.getLogger().info("NerrusBridge: Listening via Shared JsonNode dispatch.");
    }

    private <T> void deserializeAndHandle(JsonNode node, Class<T> clazz, Consumer<T> handler) {
        try {
            T payload = NerrusObjectMapper.INSTANCE.treeToValue(node, clazz);
            handler.accept(payload);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to deserialize " + clazz.getSimpleName(), e);
        }
    }

    private void handleCommandFailed(CommandFailedPayload payload) {
        if (payload == null || payload.owner() == null || payload.owner().identity() == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(payload.owner().identity().uuid());
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text("Command Failed: " + payload.reason(), NamedTextColor.RED));
            }
        });
    }

    private void handleSystemKill(SystemPersonaKillPayload payload) {
        if (payload == null || payload.sessionId() == null)
            return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            NerrusAgent target = agentService.findAgentBySessionId(payload.sessionId());
            if (target != null) {
                plugin.getLogger().warning("Executing SYSTEM_KILL for orphaned session: " + payload.sessionId());
                agentService.despawnAgent(payload.sessionId());
            } else {
                // Silently ignore if already dead (idempotency)
                if (plugin.getLogger().isLoggable(Level.FINE)) {
                    plugin.getLogger().fine("SYSTEM_KILL ignored: Session " + payload.sessionId() + " not found locally.");
                }
            }
        });
    }

    private void handleSpawnAgent(SpawnPayload payload) {
        if (payload == null)
            return;
        if (payload.owner() == null || payload.owner().identity() == null) {
            plugin.getLogger().warning("Received SpawnPayload with invalid owner structure. Ignoring.");
            return;
        }
        documentStore.fetchDocument(COLLECTION_PERSONA_MANIFESTS, payload.definitionId())
                .thenAccept(optJson -> {
                    if (optJson.isEmpty()) {
                        plugin.getLogger().warning("Cannot spawn agent " + payload.definitionId() + ": Manifest not found.");
                        return;
                    }
                    try {
                        AgentConfig manifest = NerrusObjectMapper.INSTANCE.readValue(optJson.get(), AgentConfig.class);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Location spawnLoc = resolveSpawnLocation(payload.location());
                            if (spawnLoc == null)
                                return;
                            Skin skinModel = manifest.skin();
                            SpawnAgentCommand request = new SpawnAgentCommand(
                                    manifest.name(),
                                    spawnLoc,
                                    skinModel,
                                    payload.definitionId(),
                                    payload.sessionId(),
                                    payload.owner().identity().uuid());
                            agentService.spawnAgent(request);
                        });
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to process spawn manifest", e);
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Async error handling spawn request", ex);
                    return null;
                });
    }

    private void handleDespawnAgent(DespawnPayload payload) {
        if (payload == null)
            return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            NerrusAgent target = agentService.findAgentByDefinitionId(payload.definitionId());
            if (target != null) {
                agentService.despawnAgent(target);
            } else {
                plugin.getLogger().warning(
                        "Received despawn request for unknown agent definition: " + payload.definitionId());
            }
        });
    }

    private void handlePersonaListResponse(PersonaListResponsePayload payload) {
        if (payload == null || payload.owner() == null || payload.owner().identity() == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            agentService.updatePersonaCache(payload.owner().identity().uuid(), payload.personas());
        });
    }

    private Location resolveSpawnLocation(SpawnLocation location) {
        if (location == null) {
            plugin.getLogger().severe("Spawn failed: resolveSpawnLocation received null.");
            return null;
        }
        if (location instanceof CoordinateSpawnLocation coord) {
            World world = Bukkit.getWorld(coord.world());
            if (world == null)
                return null;
            return new Location(world, coord.x(), coord.y(), coord.z(), (float) coord.yaw(), (float) coord.pitch());
        } else if (location instanceof PlayerSpawnLocation playerLoc) {
            Player target = Bukkit.getPlayer(playerLoc.target().uuid());
            if (target == null || !target.isOnline())
                return null;
            return target.getLocation();
        }
        return null;
    }

    @EventHandler
    public void onAgentSpawn(NerrusAgentSpawnEvent event) {
        PayloadFactory.createAgentStateEnvelope(event.getAgent(), SessionStatus.ACTIVE)
                .ifPresent(eventBus::broadcast);
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        PayloadFactory.createAgentStateEnvelope(event.getAgent(), SessionStatus.OFFLINE)
                .ifPresent(eventBus::broadcast);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        eventBus.broadcast(PayloadFactory.createPlayerJoinEnvelope(event.getPlayer()));
        IonCore.getInstance().getIdentityService().fetchIdentity(event.getPlayer()).thenAccept(userOpt -> {
            userOpt.ifPresent(user -> {
                eventBus.broadcast(PayloadFactory.createRequestPersonaListEnvelope(user));
            });
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        eventBus.broadcast(PayloadFactory.createPlayerQuitEnvelope(event.getPlayer()));
        agentService.clearPersonaCache(event.getPlayer().getUniqueId());
    }
}