package com.ionsignal.minecraft.ioncore.api.events;

import com.ionsignal.minecraft.ioncore.api.auth.IonIdentity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player successfully links their Minecraft account to a Web account.
 * This event is fired on the main server thread.
 */
public class IonUserLinkedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final IonIdentity identity;

    public IonUserLinkedEvent(Player player, IonIdentity identity) {
        this.player = player;
        this.identity = identity;
    }

    public Player getPlayer() {
        return player;
    }

    public IonIdentity getIdentity() {
        return identity;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
