package com.ionsignal.minecraft.ionnerrus.persona;

import org.jetbrains.annotations.NotNull;

/**
 * An interface implemented by NMS entity classes that are controlled by a Persona.
 * This allows for easy retrieval of the managing Persona from a Bukkit or NMS entity.
 */
public interface PersonaHolder {
    /**
     * Gets the Persona that controls this entity.
     *
     * @return The managing Persona instance.
     */
    @NotNull
    Persona getPersona();
}