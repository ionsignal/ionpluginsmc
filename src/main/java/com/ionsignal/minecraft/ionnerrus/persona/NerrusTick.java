package com.ionsignal.minecraft.ionnerrus.persona;

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
    }
}