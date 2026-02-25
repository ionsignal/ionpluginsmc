package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ioncore.network.model.EventEnvelope;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;
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
import com.ionsignal.minecraft.ionnerrus.network.model.Location;
import com.ionsignal.minecraft.ionnerrus.network.model.PersonaListResponsePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.Skin;
import com.ionsignal.minecraft.ionnerrus.network.model.UpdatePayload;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.SystemPersonaKillPayload;
import com.ionsignal.minecraft.ionnerrus.network.model.TeleportPayload;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.PersonaSkinData;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;

import com.google.common.base.Preconditions;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.concurrent.TimeUnit;

public class NetworkService implements Listener {
    public static final String COLLECTION_PERSONA_MANIFESTS = "persona_manifests";

    private final IonNerrus plugin;
    private final AgentService agentService;
    private final DocumentStore documentStore;
    private final PostgresEventBus eventBus;
    private final JsonService jsonService;
    private final PayloadFactory payloadFactory;
    private final ExecutorService virtualThreadExecutor;

    public NetworkService(
            IonNerrus plugin,
            AgentService agentService,
            DocumentStore documentStore,
            PostgresEventBus eventBus,
            NetworkCommandRegistrar commandRegistrar,
            JsonService jsonService,
            PayloadFactory payloadFactory,
            ExecutorService virtualThreadExecutor) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.documentStore = documentStore;
        this.eventBus = eventBus;
        this.jsonService = jsonService;
        this.payloadFactory = payloadFactory;
        this.virtualThreadExecutor = virtualThreadExecutor;
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
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_UPDATE.getValue(), node -> {
            deserializeAndHandle(node, UpdatePayload.class, this::handlePersonaUpdate);
        });
        commandRegistrar.registerHandler(IonCommandType.COMMAND_PERSONA_LIST.getValue(), node -> {
            deserializeAndHandle(node, PersonaListResponsePayload.class, this::handlePersonaListResponse);
        });
        plugin.getLogger().info("NerrusBridge: Listening via Shared JsonNode dispatch.");
    }

    private <T> void deserializeAndHandle(JsonNode node, Class<T> clazz, Consumer<T> handler) {
        try {
            T payload = jsonService.treeToValue(node, clazz);
            handler.accept(payload);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to deserialize " + clazz.getSimpleName(), e);
            try {
                if (node.has("owner") && node.has("type")) {
                    IonUser owner = jsonService.treeToValue(node.get("owner"), IonUser.class);
                    String type = node.get("type").asText();
                    failCommand(owner, type, new IllegalArgumentException("Invalid payload format: " + e.getMessage()));
                }
            } catch (Exception fallbackEx) {
                plugin.getLogger().severe("Payload was too malformed to extract owner for NACK response.");
            }
        }
    }

    private void failCommand(IonUser owner, String commandType, Throwable error) {
        Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network IO must not run on the primary thread");
        Throwable rootCause = unwrapException(error);
        String reason = rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
        String username = (owner != null && owner.identity() != null) ? owner.identity().username() : "UNKNOWN_USER";
        plugin.getLogger().warning("Command " + commandType + " failed for user " + username + ": " + reason);
        try {
            EventEnvelope nack = payloadFactory.createCommandFailedEnvelope(owner, commandType, reason);
            eventBus.broadcast(nack).join();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to broadcast SYSTEM_COMMAND_FAILED NACK: " + unwrapException(e).getMessage());
        }
    }

    private Throwable unwrapException(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException) {
            if (t.getCause() != null) {
                return unwrapException(t.getCause());
            }
        }
        return t;
    }

    private void handleCommandFailed(CommandFailedPayload payload) {
        if (payload == null || payload.owner() == null || payload.owner().identity() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(payload.owner().identity().uuid());
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text("Command Failed: " + payload.reason(), NamedTextColor.RED));
            }
        });
    }

    private void handleSystemKill(SystemPersonaKillPayload payload) {
        if (payload == null || payload.sessionId() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
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
        if (payload == null || payload.owner() == null || payload.owner().identity() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
        IonUser owner = payload.owner();
        String cmdType = payload.type();
        Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network handlers must run on Virtual Threads");
        try {
            // Synchronous DB Fetch
            var optJson = documentStore.fetchDocument(COLLECTION_PERSONA_MANIFESTS, payload.definitionId()).join();
            if (optJson.isEmpty()) {
                throw new IllegalArgumentException("Cannot spawn agent: Manifest not found in database.");
            }
            // Synchronous JSON Parse
            AgentConfig manifest = jsonService.fromJson(optJson.get(), AgentConfig.class);
            // Synchronous Bukkit Mutation
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                org.bukkit.Location spawnLoc = resolveSpawnLocation(payload.location());
                if (!spawnLoc.getChunk().isLoaded()) {
                    spawnLoc.getChunk().load(true);
                }
                Skin skinModel = manifest.skin();
                SpawnAgentCommand request = new SpawnAgentCommand(
                        manifest.name(),
                        spawnLoc,
                        skinModel,
                        payload.definitionId(),
                        payload.sessionId(),
                        owner.identity().uuid());
                agentService.spawnAgent(request);
                return null;
            }).get(5, TimeUnit.SECONDS);
            ;
        } catch (Throwable t) {
            failCommand(owner, cmdType, t);
        }
    }

    private void handleDespawnAgent(DespawnPayload payload) {
        if (payload == null || payload.owner() == null || payload.owner().identity() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
        IonUser owner = payload.owner();
        String cmdType = payload.type();
        Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network handlers must run on Virtual Threads");
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                NerrusAgent target = agentService.findAgentByDefinitionId(payload.definitionId());
                if (target != null) {
                    agentService.despawnAgent(target);
                } else {
                    throw new IllegalArgumentException("Agent not found for despawn: " + payload.definitionId());
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
            ;
        } catch (Throwable t) {
            failCommand(owner, cmdType, t);
        }
    }

    private void handlePersonaListResponse(PersonaListResponsePayload payload) {
        if (payload == null || payload.owner() == null || payload.owner().identity() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
        IonUser owner = payload.owner();
        String cmdType = payload.type();
        Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network handlers must run on Virtual Threads");
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                agentService.updatePersonaCache(payload.owner().identity().uuid(), payload.personas());
                return null;
            }).get(5, TimeUnit.SECONDS);
            ;
        } catch (Throwable t) {
            failCommand(owner, cmdType, t);
        }
    }

    private void handlePersonaUpdate(UpdatePayload payload) {
        if (payload == null || payload.owner() == null || payload.owner().identity() == null || payload.definitionId() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
        if (plugin.getLogger().isLoggable(Level.FINE)) {
            plugin.getLogger().fine("Received update ping for definition: " + payload.definitionId());
        }
        IonUser owner = payload.owner();
        String cmdType = payload.type();
        Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network handlers must run on Virtual Threads");
        try {
            var optJson = documentStore.fetchDocument(COLLECTION_PERSONA_MANIFESTS, payload.definitionId()).join();
            if (optJson.isEmpty()) {
                throw new IllegalArgumentException("Cannot update agent: Manifest not found in database.");
            }
            AgentConfig manifest = jsonService.fromJson(optJson.get(), AgentConfig.class);
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                NerrusAgent target = agentService.findAgentByDefinitionId(payload.definitionId());
                if (target == null) {
                    throw new IllegalArgumentException("Agent is not currently spawned.");
                }
                Persona persona = target.getPersona();
                boolean skinChanged = false;
                boolean nameChanged = false;
                PersonaSkinData currentSkin = persona.getSkin();
                PersonaSkinData newSkin = null;
                if (manifest.skin() != null) {
                    newSkin = new PersonaSkinData(
                            manifest.skin().mojangTextureValue(),
                            manifest.skin().mojangTextureSignature(),
                            manifest.skin().type());
                }
                if (!Objects.equals(currentSkin, newSkin)) {
                    persona.setSkin(newSkin);
                    skinChanged = true;
                    plugin.getLogger().info("Applied live skin update to agent: " + persona.getName());
                }
                String currentName = persona.getName();
                String newName = manifest.name();
                if (newName != null && !newName.equals(currentName)) {
                    persona.setName(newName);
                    nameChanged = true;
                    plugin.getLogger().info("Applied live name update to agent: " + currentName + " -> " + newName);
                }
                if (!skinChanged && !nameChanged) {
                    if (plugin.getLogger().isLoggable(Level.FINE)) {
                        plugin.getLogger().fine("Update ping processed for " + persona.getName() + " but no state changes were detected.");
                    }
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
            ;
        } catch (Throwable t) {
            failCommand(owner, cmdType, t);
        }
    }

    private org.bukkit.Location resolveSpawnLocation(SpawnLocation location) {
        if (location == null) {
            throw new IllegalArgumentException("Spawn location payload is missing.");
        }
        if (location instanceof CoordinateSpawnLocation coord) {
            World world = Bukkit.getWorld(coord.world());
            if (world == null) {
                throw new IllegalArgumentException("Target world '" + coord.world() + "' is not loaded or does not exist.");
            }
            return new org.bukkit.Location(world, coord.x(), coord.y(), coord.z(), (float) coord.yaw(), (float) coord.pitch());
        } else if (location instanceof PlayerSpawnLocation playerLoc) {
            Player target = Bukkit.getPlayer(playerLoc.target().uuid());
            if (target == null || !target.isOnline()) {
                throw new IllegalArgumentException("Target player is offline or not found.");
            }
            return target.getLocation();
        }
        throw new IllegalArgumentException("Unknown spawn location type.");
    }

    @EventHandler
    public void onAgentSpawn(NerrusAgentSpawnEvent event) {
        NerrusAgent agent = event.getAgent();
        UUID sessionId = agent.getPersona().getSessionId();
        UUID ownerId = agent.getPersona().getOwnerId();
        UUID definitionId = agent.getPersona().getDefinitionId();
        String agentName = agent.getName();
        if (sessionId == null || ownerId == null || definitionId == null) {
            plugin.getLogger().warning("Cannot broadcast spawn state for agent " + agentName + ": Missing required IDs.");
            return;
        }
        final com.ionsignal.minecraft.ionnerrus.network.model.Location locationModel = fromBukkitLocation(agent.getPersona().getLocation());
        virtualThreadExecutor.execute(() -> {
            Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network IO must not run on the primary thread");
            try {
                EventEnvelope payload = payloadFactory.createAgentStateEnvelope(
                        sessionId, ownerId, definitionId, agentName, locationModel, SessionStatus.ACTIVE);
                eventBus.broadcast(payload).join();
            } catch (Exception e) {
                Throwable rootCause = unwrapException(e);
                plugin.getLogger().severe("Orphan Prevention: Despawning agent " +
                        agentName + " (" + sessionId + ") due to state broadcast failure: " + rootCause.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    agentService.despawnAgent(sessionId);
                });
            }
        });
    }

    @EventHandler
    public void onAgentRemove(NerrusAgentRemoveEvent event) {
        NerrusAgent agent = event.getAgent();
        UUID sessionId = agent.getPersona().getSessionId();
        UUID ownerId = agent.getPersona().getOwnerId();
        UUID definitionId = agent.getPersona().getDefinitionId();
        String agentName = agent.getName();
        if (sessionId == null || ownerId == null || definitionId == null) {
            return;
        }
        final com.ionsignal.minecraft.ionnerrus.network.model.Location locationModel = fromBukkitLocation(agent.getPersona().getLocation());
        virtualThreadExecutor.execute(() -> {
            Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network IO must not run on the primary thread");
            try {
                EventEnvelope payload = payloadFactory.createAgentStateEnvelope(
                        sessionId, ownerId, definitionId, agentName, locationModel, SessionStatus.OFFLINE);
                eventBus.broadcast(payload).join();
            } catch (Exception e) {
                Throwable rootCause = unwrapException(e);
                plugin.getLogger().warning("Failed to broadcast agent remove event: " + rootCause.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final MinecraftIdentity identity = createMinecraftIdentity(event.getPlayer());
        virtualThreadExecutor.execute(() -> {
            try {
                var joinEnvelope = payloadFactory.createPlayerJoinEnvelope(identity);
                eventBus.broadcast(joinEnvelope).join();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to broadcast player join event: " + e.getMessage());
            }
        });
        IonCore.getInstance().getIdentityService().fetchIdentity(event.getPlayer()).thenAccept(userOpt -> {
            userOpt.ifPresent(user -> {
                var listEnvelope = payloadFactory.createRequestPersonaListEnvelope(user);
                virtualThreadExecutor.execute(() -> {
                    try {
                        eventBus.broadcast(listEnvelope).join();
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to broadcast persona list request: " + e.getMessage());
                    }
                });
            });
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Failed to sync identity for " + event.getPlayer().getName() + ": " + ex.getMessage());
            return null;
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        final MinecraftIdentity identity = createMinecraftIdentity(event.getPlayer());
        virtualThreadExecutor.execute(() -> {
            try {
                var quitEnvelope = payloadFactory.createPlayerQuitEnvelope(identity);
                eventBus.broadcast(quitEnvelope).join();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to broadcast player quit event: " + e.getMessage());
            }
        });
        agentService.clearPersonaCache(event.getPlayer().getUniqueId());
    }

    private Location fromBukkitLocation(@NotNull org.bukkit.Location loc) {
        return new Location(
                loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch());
    }

    private MinecraftIdentity createMinecraftIdentity(@NotNull Player player) {
        return new MinecraftIdentity(player.getUniqueId(), player.getName());
    }
}