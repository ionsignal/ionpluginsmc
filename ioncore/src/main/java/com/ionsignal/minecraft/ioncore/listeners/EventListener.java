package com.ionsignal.minecraft.ioncore.listeners;

import com.ionsignal.minecraft.ioncore.debug.DebugSessionRegistry;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Handles Bukkit events for IonCore.
 * Extracted from IonCore.java to adhere to Single Responsibility Principle.
 */
public class EventListener implements Listener {
    private final DebugSessionRegistry debugRegistry;

    public EventListener(DebugSessionRegistry debugRegistry) {
        this.debugRegistry = debugRegistry;
    }

    /**
     * Handles player logout by cleaning up their debug sessions.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (debugRegistry != null) {
            debugRegistry.cancelSession(event.getPlayer().getUniqueId());
            // Logging moved to debug level or removed to reduce noise
        }
    }
}