package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

import org.bukkit.scheduler.BukkitRunnable;

/**
 * A BukkitRunnable responsible for ticking all active Personas on the main server thread.
 */
public class NerrusTick extends BukkitRunnable {

    private final NerrusManager manager;

    public NerrusTick(NerrusManager manager) {
        this.manager = manager;
    }

    @Override
    public void run() {
        // Original persona ticking
        for (Persona persona : manager.getRegistry().getAll()) {
            if (persona.isSpawned()) {
                try {
                    persona.tick();
                } catch (Exception e) {
                    manager.getLogger().severe("Error ticking persona " + persona.getName() + " (" + persona.getUniqueId() + ")");
                    e.printStackTrace();
                }
            }
        }
        // Process agent messages to drive the message processing loop
        IonNerrus plugin = IonNerrus.getInstance();
        if (plugin != null && plugin.getAgentService() != null) {
            for (NerrusAgent agent : plugin.getAgentService().getAgents()) {
                try {
                    agent.processMessages();
                } catch (Exception e) {
                    manager.getLogger().severe("Error processing mailbox for agent " + agent.getName());
                    e.printStackTrace();
                }
            }
        }
    }
}