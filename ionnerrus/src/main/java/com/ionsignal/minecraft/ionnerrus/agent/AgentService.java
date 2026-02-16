package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusRegistry;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinData;

import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;

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
import java.util.concurrent.CompletableFuture;

public class AgentService {
    public static final String NERRUS_AGENT_METADATA = "ionnerrus_agent";

    private final IonNerrus plugin;
    private final NerrusRegistry personaRegistry;
    private final GoalRegistry goalRegistry;
    private final GoalFactory goalFactory;
    private final LLMService llmService;
    private final PostgresEventBus eventBus;
    private final Map<UUID, NerrusAgent> agents = new HashMap<>();

    public AgentService(IonNerrus plugin,
            NerrusManager nerrusManager,
            GoalRegistry goalRegistry,
            GoalFactory goalFactory,
            LLMService llmService,
            PostgresEventBus eventBus) {
        this.plugin = plugin;
        this.personaRegistry = nerrusManager.getRegistry();
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
        this.eventBus = eventBus;
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

    public CompletableFuture<Void> despawnAll() {
        if (agents.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        plugin.getLogger().info("Despawning " + agents.size() + " agent(s)...");
        List<CompletableFuture<Void>> shutdownFutures = new ArrayList<>();
        List<NerrusAgent> agentList = new ArrayList<>(agents.values());
        for (NerrusAgent agent : agentList) {
            // Trigger Async DB Update (Explicitly broadcast DESPAWNED state)
            // We do this manually here because we are bypassing the EventListener to capture the Future
            if (eventBus != null) {
                // [MODIFIED] Commented out broadcast logic.
            }
            // Remove from World (Sync)
            try {
                personaRegistry.deregister(agent.getPersona());
            } catch (Exception e) {
                plugin.getLogger().warning("Error despawning agent " + agent.getName() + ": " + e.getMessage());
            }
        }
        agents.clear();
        plugin.getLogger().info("Despawned all Nerrus agents.");
        // Return a future that completes when all DB packets are sent
        return CompletableFuture.allOf(shutdownFutures.toArray(new CompletableFuture[0]));
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