package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.network.IonEventBroker;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.records.SpawnAgentCommand;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.model.*;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.google.common.base.Preconditions;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;

public class NetworkService implements Listener {
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final IonEventBroker eventBroker;
    private final JsonService jsonService;
    private final PayloadFactory payloadFactory;
    private final ExecutorService virtualThreadExecutor;

    public NetworkService(
            IonNerrus plugin,
            AgentService agentService,
            IonEventBroker eventBroker,
            NetworkCommandRegistrar commandRegistrar,
            JsonService jsonService,
            PayloadFactory payloadFactory,
            ExecutorService virtualThreadExecutor) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.eventBroker = eventBroker;
        this.jsonService = jsonService;
        this.payloadFactory = payloadFactory;
        this.virtualThreadExecutor = virtualThreadExecutor;
        // IonCommandType Registration
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
        plugin.getLogger().info("NerrusBridge: Listening via Shared JsonNode dispatch.");
    }

    private <T> void deserializeAndHandle(JsonNode node, Class<T> clazz, Consumer<T> handler) {
        try {
            T payload = jsonService.treeToValue(node, clazz);
            handler.accept(payload);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to deserialize " + clazz.getSimpleName(), e);
            try {
                if (node.has("ownerId") && node.has("type")) {
                    UUID ownerId = UUID.fromString(node.get("ownerId").asText());
                    String type = node.get("type").asText();
                    failCommand(ownerId, type, new IllegalArgumentException("Invalid payload format: " + e.getMessage()));
                }
            } catch (Exception fallbackEx) {
                plugin.getLogger().severe("Payload was too malformed to extract ownerId for NACK response.");
            }
        }
    }

    private void failCommand(UUID ownerId, String commandType, Throwable error) {
        Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network IO must not run on the primary thread");
        Throwable rootCause = unwrapException(error);
        String reason = rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
        plugin.getLogger().warning("Command " + commandType + " failed for user " + ownerId + ": " + reason);
        try {
            var nack = payloadFactory.createCommandFailedEnvelope(ownerId, commandType, reason);
            eventBroker.broadcast(nack).join();
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
        if (payload == null || payload.ownerId() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(payload.ownerId());
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text("Command Failed: " + payload.reason(), NamedTextColor.RED));
            }
        });
    }

    private void handleSystemKill(SystemPersonaKillPayload payload) {
        if (payload == null || payload.sessionId() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing session structure.");
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            NerrusAgent target = agentService.findAgentBySessionId(payload.sessionId());
            if (target != null) {
                plugin.getLogger().warning("Executing SYSTEM_KILL for orphaned session: " + payload.sessionId());
                agentService.despawnAgent(payload.sessionId());
            } else {
                if (plugin.getLogger().isLoggable(Level.FINE)) {
                    plugin.getLogger().fine("SYSTEM_KILL ignored: Session " + payload.sessionId() + " not found locally.");
                }
            }
        });
    }

    private void handleSpawnAgent(SpawnPayload payload) {
        if (payload == null || payload.ownerId() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
        if (payload.profile() == null || payload.profile().name() == null || payload.profile().name().isBlank()) {
            failCommand(payload.ownerId(), payload.type(),
                    new IllegalArgumentException("Cannot spawn agent: ECST Profile is missing or lacks a valid name."));
            return;
        }
        UUID ownerId = payload.ownerId();
        String cmdType = payload.type();
        Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network handlers must run on Virtual Threads");
        try {
            String agentName = payload.profile().name();
            SkinPayload skinModel = payload.profile().skin();
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    org.bukkit.Location spawnLoc = resolveSpawnLocation(payload.location());
                    SpawnAgentCommand request = new SpawnAgentCommand(
                            agentName,
                            spawnLoc,
                            skinModel,
                            payload.definitionId(),
                            payload.sessionId(),
                            ownerId);
                    agentService.spawnAgent(request).exceptionally(ex -> {
                        Throwable rootCause = unwrapException(ex);
                        if (rootCause instanceof java.util.concurrent.CancellationException) {
                            if (plugin.getLogger().isLoggable(Level.FINE)) {
                                plugin.getLogger().fine("Spawn command cancelled for " + agentName + ": " + rootCause.getMessage());
                            }
                        } else {
                            virtualThreadExecutor.execute(() -> failCommand(ownerId, cmdType, rootCause));
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    virtualThreadExecutor.execute(() -> failCommand(ownerId, cmdType, t));
                }
            });
        } catch (Throwable t) {
            failCommand(ownerId, cmdType, t);
        }
    }

    private void handleDespawnAgent(DespawnPayload payload) {
        if (payload == null || payload.ownerId() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
        UUID ownerId = payload.ownerId();
        String cmdType = payload.type();
        Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network handlers must run on Virtual Threads");
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    NerrusAgent target = agentService.findAgentByDefinitionId(payload.definitionId());
                    if (target != null) {
                        agentService.despawnAgent(target);
                    } else {
                        throw new IllegalArgumentException("Agent not found for despawn: " + payload.definitionId());
                    }
                } catch (Throwable t) {
                    virtualThreadExecutor.execute(() -> failCommand(ownerId, cmdType, t));
                }
            });
        } catch (Throwable t) {
            failCommand(ownerId, cmdType, t);
        }
    }

    private void handlePersonaUpdate(UpdatePayload payload) {
        if (payload == null || payload.ownerId() == null || payload.definitionId() == null) {
            throw new IllegalArgumentException("Received payload with invalid or missing owner structure.");
        }
        if (payload.profile() == null) {
            if (plugin.getLogger().isLoggable(Level.FINE)) {
                plugin.getLogger().fine("Update ping received for " + payload.definitionId() + " but no profile data was provided.");
            }
            return;
        }
        UUID ownerId = payload.ownerId();
        String cmdType = payload.type();
        Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network handlers must run on Virtual Threads");
        try {
            String newName = payload.profile().name();
            SkinPayload newSkinPayload = payload.profile().skin();
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    NerrusAgent target = agentService.findAgentByDefinitionId(payload.definitionId());
                    if (target == null) {
                        throw new IllegalArgumentException("Agent is not currently spawned.");
                    }
                    Persona persona = target.getPersona();
                    boolean skinChanged = false;
                    boolean nameChanged = false;
                    PersonaSkinData currentSkin = persona.getSkin();
                    PersonaSkinData newSkin = null;
                    if (newSkinPayload != null) {
                        newSkin = new PersonaSkinData(
                                newSkinPayload.mojangTextureValue(),
                                newSkinPayload.mojangTextureSignature(),
                                newSkinPayload.type());
                    }
                    if (!Objects.equals(currentSkin, newSkin)) {
                        persona.setSkin(newSkin);
                        skinChanged = true;
                        plugin.getLogger().info("Applied live skin update to agent: " + persona.getName());
                    }
                    String currentName = persona.getName();
                    if (newName != null && !newName.equals(currentName)) {
                        target.assignGoal(null, null);
                        persona.setName(newName);
                        nameChanged = true;
                        plugin.getLogger().info("Applied live name update to agent: " + currentName + " -> " + newName);
                    }
                    if (!skinChanged && !nameChanged) {
                        if (plugin.getLogger().isLoggable(Level.FINE)) {
                            plugin.getLogger()
                                    .fine("Update ping processed for " + persona.getName() + " but no state changes were detected.");
                        }
                    }
                } catch (Throwable t) {
                    virtualThreadExecutor.execute(() -> failCommand(ownerId, cmdType, t));
                }
            });
        } catch (Throwable t) {
            failCommand(ownerId, cmdType, t);
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
        UUID ownerId = agent.getOwnerId();
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
                var payload = payloadFactory.createAgentStateEnvelope(
                        sessionId, ownerId, definitionId, locationModel, SessionStatus.ACTIVE);
                eventBroker.broadcast(payload).join();
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
        UUID ownerId = agent.getOwnerId();
        UUID sessionId = agent.getPersona().getSessionId();
        UUID definitionId = agent.getPersona().getDefinitionId();
        if (sessionId == null || ownerId == null || definitionId == null) {
            return;
        }
        final com.ionsignal.minecraft.ionnerrus.network.model.Location locationModel;
        if (agent.getPersona().isSpawned()) {
            locationModel = fromBukkitLocation(agent.getPersona().getLocation());
        } else {
            locationModel = new Location("unknown", 0, 0, 0, 0, 0);
        }
        virtualThreadExecutor.execute(() -> {
            Preconditions.checkState(!Bukkit.isPrimaryThread(), "Network IO must not run on the primary thread");
            try {
                var payload = payloadFactory.createAgentStateEnvelope(
                        sessionId, ownerId, definitionId, locationModel, SessionStatus.OFFLINE);
                eventBroker.broadcast(payload).join();
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
                var joinPayload = payloadFactory.createPlayerJoinEnvelope(identity);
                eventBroker.broadcast(joinPayload).join();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to broadcast player join event: " + e.getMessage());
            }
        });
        IonCore.getInstance().getIdentityService().fetchIdentity(event.getPlayer()).thenAccept(userOpt -> {
            userOpt.ifPresent(user -> {
                UUID webOwnerId = user.id();
                UUID minecraftUuid = user.identity().uuid();
                ObjectNode req = jsonService.getObjectMapper().createObjectNode();
                req.put("ownerId", webOwnerId.toString());
                String tenantId = IonCore.getInstance().getTenantConfig().getTenantId();
                String subject = "ion.req." + tenantId + ".persona.list";
                eventBroker.requestAsync(subject, req).thenAccept(responseNode -> {
                    if (responseNode == null)
                        return;
                    List<PersonaListItem> personas = new ArrayList<>();
                    if (responseNode.isArray()) {
                        for (JsonNode element : responseNode) {
                            try {
                                personas.add(jsonService.getObjectMapper().treeToValue(element, PersonaListItem.class));
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to parse persona list item: " + e.getMessage());
                            }
                        }
                    }
                    agentService.updatePersonaCache(minecraftUuid, personas);
                }).exceptionally(ex -> {
                    plugin.getLogger().warning(
                            "NATS RPC timeout/error fetching persona list for " + event.getPlayer().getName() + ": " + ex.getMessage());
                    return null;
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
                var quitPayload = payloadFactory.createPlayerQuitEnvelope(identity);
                eventBroker.broadcast(quitPayload).join();
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