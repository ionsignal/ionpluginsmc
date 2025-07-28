package com.ionsignal.minecraft.ionnerrus.agent;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusRegistry;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AgentService {
    public static final String NERRUS_AGENT_METADATA = "ionnerrus_agent";

    private final IonNerrus plugin;
    private final NerrusRegistry personaRegistry;
    private final Map<UUID, NerrusAgent> agents = new HashMap<>();

    public AgentService(IonNerrus plugin, NerrusManager nerrusManager) {
        this.plugin = plugin;
        this.personaRegistry = nerrusManager.getRegistry();
    }

    /* 
     * TODO: implement agent persistence later, let's disable for now for speeding up development.
     * 
    public void loadAgents() {
        plugin.getLogger().info("Loading Nerrus agents from registry...");
        int count = 0;
        for (Persona persona : IonNerrus.getNerrusRegistry()) {
            if (npc.data().has(NERRUS_AGENT_METADATA)) {
                Persona persona = personaRegistry.createPersona(EntityType.PLAYER, persona.getName());
                persona.getMetadata().setPersistent(NERRUS_AGENT_METADATA, true);
                SkinTrait skinTrait = persona.getTraitNullable(SkinTrait.class);
                if (skinTrait != null && skinTrait.getSkinName() != null) {
                    NerrusManager.getInstance().getSkinCache().fetchSkin(skinTrait.getSkinName()).thenAccept(skinData -> {
                        if (skinData != null) {
                            plugin.getMainThreadExecutor().execute(() -> persona.setSkin(skinData));
                        }
                    });
                }
                if (npc.isSpawned()) {
                    persona.spawn(npc.getStoredLocation());
                }
                NerrusAgent agent = new NerrusAgent(npc, persona, plugin);
                agents.put(npc.getUniqueId(), agent);
                count++;
            }
        }
        plugin.getLogger().info("Loaded " + count + " Nerrus agents.");
    }
    */

    public NerrusAgent spawnAgent(String name, Location location, String skinNameToFetch) {
        Persona persona = personaRegistry.createPersona(EntityType.PLAYER, name);
        persona.getMetadata().setPersistent(NERRUS_AGENT_METADATA, true);

        NerrusAgent agent = new NerrusAgent(persona, plugin);
        agents.put(persona.getUniqueId(), agent);

        NerrusManager.getInstance().getSkinCache().fetchSkin(skinNameToFetch).thenAccept(skinData -> {
            if (skinData != null) {
                persona.setSkin(skinData);
            } else {
                plugin.getLogger().warning("Could not fetch skin for '" + skinNameToFetch + "'. Spawning with default Steve/Alex skin.");
            }
            plugin.getMainThreadExecutor().execute(() -> {
                persona.spawn(location);
                plugin.getLogger().info("Successfully spawned agent: " + name);
            });
        });
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
        agents.clear();
        personaRegistry.clear();
        plugin.getLogger().info("Despawned all Nerrus agents.");
    }
}