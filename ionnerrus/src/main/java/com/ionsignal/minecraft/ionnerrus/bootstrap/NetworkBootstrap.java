package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.network.IonCommandTypeResolver;
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

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.logging.Level;

// TODO: THIS CLASS IF ROUGH DRAFT

public class NetworkBootstrap {
    public static final String COLLECTION_PERSONA_MANIFESTS = "persona_manifests";

    private final IonNerrus plugin;
    private final AgentService agentService;
    private final DocumentStore documentStore;
    private final NetworkCommandRegistrar commandRegistrar;

    public NetworkBootstrap(IonNerrus plugin,
            AgentService agentService,
            DocumentStore documentStore,
            NetworkCommandRegistrar commandRegistrar) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.documentStore = documentStore;
        this.commandRegistrar = commandRegistrar;
    }

    public void registerAll() {
        // 1. Type Registration (Teach Jackson)
        // We wrap this in a try-catch to handle plugin reloads where Core might still hold references.
        // If IonNerrus is reloaded, the Core's static registry will conflict with the new classloader's
        // classes.
        try {
            IonCommandTypeResolver.registerType(IonCommandType.COMMAND_PERSONA_SPAWN.getValue(), SpawnPayload.class);
            IonCommandTypeResolver.registerType(IonCommandType.COMMAND_PERSONA_DESPAWN.getValue(), DespawnPayload.class);
            IonCommandTypeResolver.registerType(IonCommandType.COMMAND_PERSONA_TELEPORT.getValue(), TeleportPayload.class);
            IonCommandTypeResolver.registerType(IonCommandType.COMMAND_PERSONA_SKIN_UPDATE.getValue(), SkinUpdatePayload.class);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("CRITICAL: Network Type Registry Collision. " +
                    "This usually happens after a plugin reload. Please RESTART the server to ensure network stability.");
        }
        // 2. Handler Registration (Teach Core)
        // Use injected registrar with typed class keys to route commands to specific methods.
        commandRegistrar.registerHandler(SpawnPayload.class, this::handleSpawnAgent);
        commandRegistrar.registerHandler(DespawnPayload.class, this::handleDespawnAgent);
        plugin.getLogger().info("NetworkBootstrap: Listening for SPAWN_AGENT and DESPAWN_AGENT.");
    }

    private void handleSpawnAgent(SpawnPayload payload) {
        if (payload == null)
            return;
        // 1. Fetch Manifest (Async) to get the Agent's Name
        // The SpawnPayload contains IDs but not the display name, which lives in the manifest.
        documentStore.fetchDocument(COLLECTION_PERSONA_MANIFESTS, payload.definitionId())
                .thenAccept(optJson -> {
                    if (optJson.isEmpty()) {
                        plugin.getLogger().warning("Cannot spawn agent " + payload.definitionId() + ": Manifest not found.");
                        return;
                    }

                    try {
                        // Deserialize Manifest
                        var jsonService = IonCore.getInstance().getServiceContainer().getJsonService();
                        AgentConfig manifest = jsonService.fromJson(optJson.get(), AgentConfig.class);
                        // 2. Switch to Main Thread for Spawning
                        // Critical: We must not touch Bukkit API (Worlds, Players) from the async thread.
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Location spawnLoc = resolveSpawnLocation(payload.location());
                            if (spawnLoc == null) {
                                return; // Error logged in resolveSpawnLocation
                            }
                            // Construct the internal request
                            AgentSpawnRequest request = new AgentSpawnRequest(
                                    manifest.name(),
                                    spawnLoc,
                                    null, // Skin is handled by AgentService fetching via name/manifest
                                    payload.definitionId(),
                                    payload.sessionId(),
                                    payload.owner().uuid());

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
                // This is common if the server restarted and the web dashboard is out of sync.
                // We log it as a warning but it's effectively a successful "ensure despawned" operation.
                plugin.getLogger().warning("Received despawn request for unknown agent definition: " + payload.definitionId());
            }
        });
    }

    /**
     * Resolves the polymorphic SpawnLocation into a concrete Bukkit Location.
     * Performs validation on Worlds and Players.
     *
     * @param location
     *            The generated model location.
     * @return A valid Bukkit Location, or null if resolution failed.
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