package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

@SuppressWarnings("unused")
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
        // Use injected registrar instead of static lookup
        commandRegistrar.registerHandler("command:persona:spawn", this::handleSpawnAgent);
        commandRegistrar.registerHandler("command:persona:despawn", this::handleDespawnAgent);
        plugin.getLogger().info("NetworkBootstrap: Listening for SPAWN_AGENT and DESPAWN_AGENT.");
    }

    private void handleSpawnAgent(String jsonPayload) {
        // TODO
    }

    private void handleDespawnAgent(String jsonPayload) {
        // TODO
    }
}