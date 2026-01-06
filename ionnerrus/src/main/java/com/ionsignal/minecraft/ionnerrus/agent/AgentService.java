package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentRemoveEvent;
import com.ionsignal.minecraft.ionnerrus.api.events.NerrusAgentSpawnEvent;
import com.ionsignal.minecraft.ionnerrus.network.NetworkBroadcaster;
import com.ionsignal.minecraft.ionnerrus.network.messages.AgentTelemetry;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusRegistry;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinData;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.scheduler.BukkitTask;
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
    private final NetworkBroadcaster broadcaster;
    private final Map<UUID, NerrusAgent> agents = new HashMap<>();
    
    private BukkitTask telemetryTask;

    public AgentService(IonNerrus plugin, NerrusManager nerrusManager, GoalRegistry goalRegistry, GoalFactory goalFactory,
            LLMService llmService, NetworkBroadcaster broadcaster) {
        this.plugin = plugin;
        this.personaRegistry = nerrusManager.getRegistry();
        this.goalRegistry = goalRegistry;
        this.goalFactory = goalFactory;
        this.llmService = llmService;
        this.broadcaster = broadcaster;
        
        startTelemetryLoop();
    }

    /**
     * Starts a repeating task to push agent telemetry to IonCore.
     * Replaces the old "Register Source" mechanism.
     */
    private void startTelemetryLoop() {
        this.telemetryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!Bukkit.getPluginManager().isPluginEnabled("IonCore")) return;
            
            for (NerrusAgent agent : agents.values()) {
                if (agent.getPersona().isSpawned()) {
                    try {
                        AgentTelemetry telemetry = AgentTelemetry.from(agent);
                        // Push to IonCore Telemetry Manager
                        IonCore.getInstance().getTelemetryManager().sendTelemetry("AGENT_STATE", telemetry);
                    } catch (Exception e) {
                        // Suppress telemetry errors to avoid console spam
                    }
                }
            }
        }, 20L, 20L); // Run every 1 second (20 ticks)
    }

    /**
     * Spawns an agent with a specific skin name to fetch from Mojang API.
     *
     * @param name
     *            The name of the agent.
     * @param location
     *            The spawn location.
     * @param skinNameToFetch
     *            The username to fetch the skin from. If null, defaults to the agent name.
     * @return The created agent instance.
     */
    public NerrusAgent spawnAgent(String name, Location location, @Nullable String skinNameToFetch) {
        NerrusAgent agent = createAgentBase(name);
        String lookupName = (skinNameToFetch != null && !skinNameToFetch.isEmpty()) ? skinNameToFetch : name;

        // Async Fetch -> Main Thread Spawn
        NerrusManager.getInstance().getSkinCache().fetchSkin(lookupName).thenAcceptAsync(skinData -> {
            if (skinData == null) {
                plugin.getLogger().warning("Could not fetch skin for '" + lookupName + "'. Spawning with default Steve/Alex skin.");
            }
            finalizeSpawn(agent, location, skinData);
        }, plugin.getMainThreadExecutor());

        return agent;
    }

    /**
     * Spawns an agent with pre-existing SkinData (e.g. from a web payload).
     *
     * @param name
     *            The name of the agent.
     * @param location
     *            The spawn location.
     * @param skinData
     *            The explicit skin data to apply.
     * @return The created agent instance.
     */
    public NerrusAgent spawnAgent(String name, Location location, SkinData skinData) {
        NerrusAgent agent = createAgentBase(name);
        // Execute on main thread to ensure NMS safety during spawn
        if (Bukkit.isPrimaryThread()) {
            finalizeSpawn(agent, location, skinData);
        } else {
            plugin.getMainThreadExecutor().execute(() -> finalizeSpawn(agent, location, skinData));
        }
        return agent;
    }

    /**
     * Convenience method to spawn an agent using its name for the skin lookup.
     */
    public NerrusAgent spawnAgent(String name, Location location) {
        return spawnAgent(name, location, (String) null);
    }

    /**
     * Internal helper to create the Agent and Persona structure.
     * Does NOT spawn the entity in the world.
     */
    private NerrusAgent createAgentBase(String name) {
        Persona persona = personaRegistry.createPersona(EntityType.PLAYER, name, Optional.empty());
        persona.getMetadata().setPersistent(NERRUS_AGENT_METADATA, true);
        NerrusAgent agent = new NerrusAgent(persona, plugin, goalRegistry, goalFactory, llmService, broadcaster);
        agents.put(persona.getUniqueId(), agent);
        return agent;
    }

    /**
     * Internal helper to finalize the spawn process on the main thread.
     * Applies skin, spawns entity, starts agent loop.
     */
    private void finalizeSpawn(NerrusAgent agent, Location location, @Nullable SkinData skinData) {
        Persona persona = agent.getPersona();

        // Concurrency check: Ensure agent wasn't removed while waiting for skin/thread
        if (!agents.containsKey(persona.getUniqueId())) {
            return;
        }

        if (skinData != null) {
            persona.setSkin(skinData);
        }

        try {
            persona.spawn(location);
            agent.start();
            // REMOVED: Legacy Telemetry Registration
            plugin.getLogger().info("Successfully spawned agent: " + agent.getName());
            Bukkit.getPluginManager().callEvent(new NerrusAgentSpawnEvent(agent));
        } catch (Exception e) {
            plugin.getLogger().severe("Error spawning agent " + agent.getName() + ": " + e.getMessage());
            e.printStackTrace();
            // Cleanup failed agent
            removeAgent(agent.getName());
        }
    }

    public boolean removeAgent(String name) {
        NerrusAgent agent = findAgentByName(name);
        if (agent != null) {
            // REMOVED: Legacy Telemetry Unregistration
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
                // REMOVED: Legacy Telemetry Unregistration
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
        if (telemetryTask != null) {
            telemetryTask.cancel();
        }
        if (!agents.isEmpty()) {
            plugin.getLogger().warning("AgentService.shutdown() found " + agents.size() + " remaining agents - this shouldn't happen!");
            despawnAll();
        }
    }
}