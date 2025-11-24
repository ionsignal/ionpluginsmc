package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusRegistry;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AgentService {
    public static final String NERRUS_AGENT_METADATA = "ionnerrus_agent";

    private final IonNerrus plugin;
    private final NerrusRegistry personaRegistry;
    private final GoalRegistry goalRegistry;
    private final GoalFactory goalFactory;
    private final LLMService llmService;
    private final Map<UUID, NerrusAgent> agents = new HashMap<>();

    public AgentService(IonNerrus plugin, NerrusManager nerrusManager, GoalRegistry goalRegistry, GoalFactory goalFactory,
            LLMService llmService) {
        this.plugin = plugin;
        this.personaRegistry = nerrusManager.getRegistry();
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
    }

    /*
     * Implement agent persistence later, let's disable for now for speeding up development.
     */
    public NerrusAgent spawnAgent(String name, Location location, String skinNameToFetch) {
        Persona persona = personaRegistry.createPersona(EntityType.PLAYER, name);
        persona.getMetadata().setPersistent(NERRUS_AGENT_METADATA, true);
        NerrusAgent agent = new NerrusAgent(persona, plugin, goalRegistry, goalFactory, llmService);
        agents.put(persona.getUniqueId(), agent);
        NerrusManager.getInstance().getSkinCache().fetchSkin(skinNameToFetch).thenAcceptAsync(skinData -> {
            if (skinData != null) {
                persona.setSkin(skinData);
            } else {
                plugin.getLogger().warning("Could not fetch skin for '" + skinNameToFetch + "'. Spawning with default Steve/Alex skin.");
            }
            try {
                persona.spawn(location);
                agent.start();
                plugin.getLogger().info("Successfully spawned agent: " + name);
            } catch (Exception e) {
                plugin.getLogger().severe("Error spawning agent " + name + ": " + e.getMessage());
            }
        }, plugin.getMainThreadExecutor());
        return agent;
    }

    public NerrusAgent spawnAgent(String name, Location location) {
        return spawnAgent(name, location, null);
    }

    public boolean removeAgent(String name) {
        NerrusAgent agent = findAgentByName(name);
        if (agent != null) {
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
                // Persona.despawn() now handles stopping all physical operations safely
                personaRegistry.deregister(agent.getPersona());
            } catch (Exception e) {
                plugin.getLogger().warning("Error despawning agent " + agent.getName() + ": " + e.getMessage());
            }
        }
        agents.clear();
        plugin.getLogger().info("Despawned all Nerrus agents.");
    }

    /**
     * Shuts down the agent service.
     * Note: Agents are despawned early in IonNerrus.onDisable() to avoid chunk system race conditions.
     * This method just ensures cleanup of any remaining state.
     */
    public void shutdown() {
        plugin.getLogger().info("Shutting down AgentService...");
        // Agents should already be despawned by now (see IonNerrus.onDisable)
        if (!agents.isEmpty()) {
            plugin.getLogger().warning("AgentService.shutdown() found " + agents.size() + " remaining agents - this shouldn't happen!");
            despawnAll();
        }
    }
}