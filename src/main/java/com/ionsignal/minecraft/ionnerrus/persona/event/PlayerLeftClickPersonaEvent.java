package com.ionsignal.minecraft.ionnerrus.persona.event;

import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a player left-clicks (attacks) a Persona.
 */
public class PlayerLeftClickPersonaEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Persona persona;
    private boolean isCancelled;

    public PlayerLeftClickPersonaEvent(@NotNull Player who, @NotNull Persona persona) {
        super(who);
        this.persona = persona;
    }

    /**
     * Gets the Persona that was left-clicked.
     *
     * @return The Persona involved in this event.
     */
    public @NotNull Persona getPersona() {
        return persona;
    }

    @Override
    public boolean isCancelled() {
        return this.isCancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.isCancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}