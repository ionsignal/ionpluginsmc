package com.ionsignal.minecraft.ionnerrus.persona.listeners;

import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.PersonaHolder;
import com.ionsignal.minecraft.ionnerrus.persona.event.PlayerLeftClickPersonaEvent;
import com.ionsignal.minecraft.ionnerrus.persona.event.PlayerRightClickPersonaEvent;

import net.minecraft.server.level.ServerPlayer;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public class PersonaInteractionListener implements Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (clicked instanceof CraftPlayer craftPlayer && craftPlayer.getHandle() instanceof PersonaHolder personaHolder) {
            Player player = event.getPlayer();
            Persona persona = personaHolder.getPersona();
            PlayerRightClickPersonaEvent customEvent = new PlayerRightClickPersonaEvent(player, persona);
            customEvent.callEvent();
            if (!customEvent.isCancelled()) {
                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
                PersonaEntity personaEntity = persona.getPersonaEntity();
                if (personaEntity != null) {
                    serverPlayer.openMenu(personaEntity);
                }
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damaged = event.getEntity();
        if (event.getDamager() instanceof Player damager && damaged instanceof CraftPlayer craftPlayer
                && craftPlayer.getHandle() instanceof PersonaHolder personaHolder) {
            Persona persona = personaHolder.getPersona();
            PlayerLeftClickPersonaEvent customEvent = new PlayerLeftClickPersonaEvent(damager, persona);
            customEvent.callEvent();
            if (!customEvent.isCancelled()) {
                if (persona.getPhysicalBody().state().isInventoryOpen())
                    return; // Don't interrupt if inventory is open
                if (persona.isSpawned()) {
                    // TODO: token?
                    // persona.getPhysicalBody().orientation().face(damager);
                }
            }
            // Always cancel the original event to prevent damage and knockback.
            event.setCancelled(true);
        }
    }
}