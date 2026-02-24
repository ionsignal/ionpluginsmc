package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.commands.SpawnAgentCommand;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentConfig;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusRegistry;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinData;

import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;

import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;

import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AgentService {
    public static final String NERRUS_AGENT_METADATA = "ionnerrus_agent";

    private final IonNerrus plugin;
    private final NerrusRegistry personaRegistry;
    private final GoalRegistry goalRegistry;
    private final GoalFactory goalFactory;
    private final LLMService llmService;

    // Persona Memory Management
    private final Map<UUID, NerrusAgent> agents = new HashMap<>();
    private final Map<UUID, List<AgentConfig>> availablePersonasCache = new ConcurrentHashMap<>();

    @SuppressWarnings("unused")
    private final PostgresEventBus eventBus;

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

    public NerrusAgent spawnAgent(SpawnAgentCommand command) {
        Preconditions.checkState(Bukkit.isPrimaryThread(), "Agents must be spawned on the main thread");
        NerrusAgent existingClone = findAgentByDefinitionId(command.definitionId());
        if (existingClone != null) {
            plugin.getLogger().warning("Highlander Rule: Found existing physical clone for definition "
                    + command.definitionId() + ". Despawning old clone before spawning new session.");
            despawnAgent(existingClone);
        }
        if (findAgentBySessionId(command.sessionId()) != null) {
            plugin.getLogger().warning("Agent with session ID " + command.sessionId() + " is already active.");
            return findAgentBySessionId(command.sessionId());
        }
        Persona persona = personaRegistry.createPersona(EntityType.PLAYER, command.name(), Optional.empty());
        persona.getMetadata().setPersistent(NERRUS_AGENT_METADATA, true);
        persona.setSessionId(command.sessionId());
        persona.setDefinitionId(command.definitionId());
        persona.setOwnerId(command.ownerId());
        if (command.skin() != null) {
            persona.setSkin(new SkinData(command.skin().value(), command.skin().signature()));
        }
        NerrusAgent agent = new NerrusAgent(persona, plugin, goalRegistry, goalFactory, llmService);
        agents.put(persona.getUniqueId(), agent);
        try {
            persona.spawn(command.location());
            agent.start();
            plugin.getLogger().info("Successfully spawned agent: " + agent.getName());
            Bukkit.getPluginManager().callEvent(new NerrusAgentSpawnEvent(agent));
        } catch (Exception e) {
            plugin.getLogger().severe("Error spawning agent " + agent.getName() + ": " + e.getMessage());
            e.printStackTrace();
            despawnAgent(command.sessionId());
        }
        return agent;
    }

    public boolean despawnAgent(UUID sessionId) {
        NerrusAgent agent = findAgentBySessionId(sessionId);
        return despawnAgent(agent);
    }

    public boolean despawnAgent(NerrusAgent agent) {
        if (agent != null) {
            Bukkit.getPluginManager().callEvent(new NerrusAgentRemoveEvent(agent));
            agents.remove(agent.getPersona().getUniqueId());
            personaRegistry.deregister(agent.getPersona());
            plugin.getLogger().info("Despawned Nerrus agent: " + agent.getName() + " (Session: " + agent.getPersona().getSessionId() + ")");
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

    public NerrusAgent findAgentBySessionId(UUID sessionId) {
        return agents.values().stream()
                .filter(a -> sessionId.equals(a.getPersona().getSessionId()))
                .findFirst()
                .orElse(null);
    }

    public NerrusAgent findAgentByDefinitionId(UUID definitionId) {
        return agents.values().stream()
                .filter(a -> definitionId.equals(a.getPersona().getDefinitionId()))
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
            if (agent.getPersona().getSessionId() != null) {
                despawnAgent(agent.getPersona().getSessionId());
            } else {
                Bukkit.getPluginManager().callEvent(new NerrusAgentRemoveEvent(agent));
                agents.remove(agent.getPersona().getUniqueId());
                personaRegistry.deregister(agent.getPersona());
            }
        }
        agents.clear();
        plugin.getLogger().info("Despawned all Nerrus agents.");
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

    public void updatePersonaCache(UUID ownerMcUuid, List<AgentConfig> personas) {
        availablePersonasCache.put(ownerMcUuid, personas);
        plugin.getLogger().info("Updated Persona Cache for player " + ownerMcUuid + " (" + personas.size() + " personas)");
    }

    public List<AgentConfig> getCachedPersonas(UUID ownerMcUuid) {
        return availablePersonasCache.getOrDefault(ownerMcUuid, new ArrayList<>());
    }

    public void clearPersonaCache(UUID ownerMcUuid) {
        availablePersonasCache.remove(ownerMcUuid);
        plugin.getLogger().info("Cleared Persona Cache for player " + ownerMcUuid);
    }
}