package com.ionsignal.minecraft.ioncore.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;

/**
 * Fired when a player successfully links their Minecraft account to a Web account.
 */
public class IonUserLinkedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final MinecraftIdentity identity;

    public IonUserLinkedEvent(Player player, MinecraftIdentity identity) {
        this.player = player;
        this.identity = identity;
    }

    public Player getPlayer() {
        return player;
    }

    public MinecraftIdentity getIdentity() {
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
