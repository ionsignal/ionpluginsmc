package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.schema.Incoming;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusRegistry;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinData;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class AgentService {
    public static final String NERRUS_AGENT_METADATA = "ionnerrus_agent";

    private final IonNerrus plugin;
    private final NerrusRegistry personaRegistry;
    private final GoalRegistry goalRegistry;
    private final GoalFactory goalFactory;
    private final LLMService llmService;
    private final Map<UUID, NerrusAgent> agents = new HashMap<>();

    public AgentService(IonNerrus plugin,
            NerrusManager nerrusManager,
            GoalRegistry goalRegistry,
            GoalFactory goalFactory,
            LLMService llmService) {
        this.plugin = plugin;
        this.personaRegistry = nerrusManager.getRegistry();
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
    }

    /**
     * Spawns an agent based on the configuration received from the Web Dashboard via PostgreSQL.
     * This is the primary method for the new architecture.
     *
     * @param signalPayload
     *            The spawn command details (Location, Owner, Definition ID).
     * @param configPayload
     *            The synced agent configuration (Skin, Name) from 'persona_manifests'.
     * @param location
     *            The resolved Bukkit location.
     * 
     * @return The spawned agent instance.
     */
    public NerrusAgent spawnAgent(Incoming.SpawnPayload signalPayload, Incoming.AgentSyncPayload configPayload, Location location) {
        NerrusAgent agent = createAgentBase(signalPayload.name());
        // Link the Web Definition ID to the Runtime Persona
        agent.getPersona().setDefinitionId(signalPayload.definitionId());
        // Map Skin Data
        SkinData skinData = null;
        if (configPayload.skin() != null) {
            String texture = configPayload.skin().value();
            String signature = configPayload.skin().signature();
            // Only apply skin if texture value is present
            if (texture != null && !texture.isBlank()) {
                skinData = new SkinData(texture, signature);
            } else {
                // This is valid for default skins (STEVE/ALEX), so we log info, not warning
                plugin.getLogger().info("Agent configuration has 'skin' object but 'value' is empty. Using default skin.");
            }
        }
        // Ensure spawning happens on the main thread
        final SkinData finalSkin = skinData;
        if (Bukkit.isPrimaryThread()) {
            finalizeSpawn(agent, location, finalSkin);
        } else {
            plugin.getMainThreadExecutor().execute(() -> finalizeSpawn(agent, location, finalSkin));
        }
        return agent;
    }

    public NerrusAgent spawnAgent(String name, Location location, @Nullable String skinNameToFetch) {
        NerrusAgent agent = createAgentBase(name);
        String lookupName = (skinNameToFetch != null && !skinNameToFetch.isEmpty()) ? skinNameToFetch : name;
        NerrusManager.getInstance().getSkinCache().fetchSkin(lookupName).thenAcceptAsync(skinData -> {
            if (skinData == null) {
                plugin.getLogger().warning("Could not fetch skin for '" + lookupName + "'. Spawning with default skin.");
            }
            finalizeSpawn(agent, location, skinData);
        }, plugin.getMainThreadExecutor());
        return agent;
    }

    private NerrusAgent createAgentBase(String name) {
        Persona persona = personaRegistry.createPersona(EntityType.PLAYER, name, Optional.empty());
        persona.getMetadata().setPersistent(NERRUS_AGENT_METADATA, true);
        // Removed broadcaster from NerrusAgent constructor
        NerrusAgent agent = new NerrusAgent(persona, plugin, goalRegistry, goalFactory, llmService);
        agents.put(persona.getUniqueId(), agent);
        return agent;
    }

    private void finalizeSpawn(NerrusAgent agent, Location location, @Nullable SkinData skinData) {
        Persona persona = agent.getPersona();
        if (!agents.containsKey(persona.getUniqueId())) {
            return;
        }
        if (skinData != null) {
            persona.setSkin(skinData);
        }
        try {
            persona.spawn(location);
            agent.start();
            plugin.getLogger().info("Successfully spawned agent: " + agent.getName());
            Bukkit.getPluginManager().callEvent(new NerrusAgentSpawnEvent(agent));
        } catch (Exception e) {
            plugin.getLogger().severe("Error spawning agent " + agent.getName() + ": " + e.getMessage());
            e.printStackTrace();
            removeAgent(agent.getName());
        }
    }

    public void updateAgentSkin(NerrusAgent agent, Incoming.AgentSyncPayload.Skin skin) {
        if (skin == null || skin.value() == null)
            return;
        SkinData skinData = new SkinData(skin.value(), skin.signature());
        agent.getPersona().setSkin(skinData);
        plugin.getLogger().info("Updated skin for agent: " + agent.getName());
    }

    public boolean removeAgent(String name) {
        NerrusAgent agent = findAgentByName(name);
        if (agent != null) {
            Bukkit.getPluginManager().callEvent(new NerrusAgentRemoveEvent(agent));
            agents.remove(agent.getPersona().getUniqueId());
            personaRegistry.deregister(agent.getPersona());
            plugin.getLogger().info("Removed Nerrus agent: " + name);
            return true;
        }
        return false;
    }

    public NerrusAgent findAgentByName(String name) {
        return agents.values().stream()
                .filter(agent -> agent.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public Collection<NerrusAgent> getAgents() {
        return agents.values();
    }

    public void despawnAll() {
        if (agents.isEmpty()) {
            return;
        }
        plugin.getLogger().info("Despawning " + agents.size() + " agent(s)...");
        List<NerrusAgent> agentList = new ArrayList<>(agents.values());
        for (NerrusAgent agent : agentList) {
            try {
                personaRegistry.deregister(agent.getPersona());
            } catch (Exception e) {
                plugin.getLogger().warning("Error despawning agent " + agent.getName() + ": " + e.getMessage());
            }
        }
        agents.clear();
        plugin.getLogger().info("Despawned all Nerrus agents.");
    }

    public void shutdown() {
        plugin.getLogger().info("Shutting down AgentService...");
        if (!agents.isEmpty()) {
            plugin.getLogger().warning("AgentService.shutdown() found " + agents.size() +
                    " remaining agents - this shouldn't happen!");
            despawnAll();
        }
    }
}