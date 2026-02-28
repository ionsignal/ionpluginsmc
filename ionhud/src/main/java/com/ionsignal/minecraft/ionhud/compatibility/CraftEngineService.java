package com.ionsignal.minecraft.ionnerrus.compatibility;

import org.bukkit.entity.Player;

/**
 * Service interface for interacting with the CraftEngine plugin. This abstraction ensures that the
 * core plugin code does not have hard references to CraftEngine classes.
 */
public interface CraftEngineService {
    /**
     * Registers a Persona entity with CraftEngine as a "Fake" user which allows the Persona to interact
     * with custom blocks (breaking/placing) correctly.
     *
     * @param personaEntity
     *            The Bukkit Player entity representing the Persona.
     */
    void registerPersona(Player personaEntity);

    /**
     * Unregisters a Persona entity from CraftEngine and should be called when the Persona despawns.
     *
     * @param personaEntity
     *            The Bukkit Player entity representing the Persona.
     */
    void unregisterPersona(Player personaEntity);

    /**
     * No-operation implementation used when CraftEngine is not installed.
     */
    class NoOp implements CraftEngineService {
        @Override
        public void registerPersona(Player personaEntity) {
            // Do nothing
        }

        @Override
        public void unregisterPersona(Player personaEntity) {
            // Do nothing
        }
    }
}