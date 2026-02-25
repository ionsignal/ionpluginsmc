package com.ionsignal.minecraft.ioncore.listeners;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class IdentityListener implements Listener {
    private final IdentityService identityService;

    public IdentityListener(IdentityService identityService) {
        this.identityService = identityService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Async fetch
        identityService.fetchIdentity(event.getPlayer()).thenAccept(userOpt -> {
            // Check if Optional is empty (Unlinked)
            if (userOpt.isEmpty()) {
                // If not linked, prompt them
                identityService.initiateLinkingProcess(event.getPlayer());
            }
        });
    }

    // Priority set to MONITOR to ensure other listeners (NetworkService)
    // have processed the event before we wipe the cache.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        identityService.invalidate(event.getPlayer().getUniqueId());
    }
}