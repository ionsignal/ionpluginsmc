package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

import org.bukkit.scheduler.BukkitRunnable;

public class NerrusTick extends BukkitRunnable {
    private final NerrusManager manager;

    public NerrusTick(NerrusManager manager) {
        this.manager = manager;
    }

    @Override
    public void run() {
        // Tick Physical Personas (Physics/Movement)
        for (Persona persona : manager.getRegistry().getAll()) {
            if (persona.isSpawned()) {
                try {
                    persona.tick();
                } catch (Exception e) {
                    manager.getLogger().severe("Error ticking persona " + persona.getName());
                    e.printStackTrace();
                }
            }
        }
        // Tick Agents (Brain/Network)
        IonNerrus plugin = IonNerrus.getInstance();
        if (plugin != null && plugin.getAgentService() != null) {
            for (NerrusAgent agent : plugin.getAgentService().getAgents()) {
                try {
                    agent.tick();
                } catch (Exception e) {
                    manager.getLogger().severe("Error ticking agent " + agent.getName());
                    e.printStackTrace();
                }
            }
        }
    }
}