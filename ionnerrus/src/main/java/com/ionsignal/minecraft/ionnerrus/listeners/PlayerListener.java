package com.ionsignal.minecraft.ionnerrus.listeners;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {
    private final NerrusManager nerrusManager;

    public PlayerListener(IonNerrus plugin, NerrusManager nerrusManager) {
        this.nerrusManager = nerrusManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (nerrusManager == null)
            return;
        for (Persona persona : nerrusManager.getRegistry().getAll()) {
            if (persona.isSpawned() && persona.getPersonaEntity() != null) {
                persona.getPersonaEntity().revokeVisibility(player);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (nerrusManager == null) {
            return;
        }
        for (Persona persona : nerrusManager.getRegistry().getAll()) {
            if (persona.isSpawned()) {
                persona.showToPlayer(player);
            }
        }
    }
}