package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ionnerrus.persona.action.ActionController;
import com.ionsignal.minecraft.ionnerrus.persona.animation.PlayerAnimation;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Navigator;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinData;

import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class Persona {
    private final UUID uuid;
    private final EntityType entityType;
    private final NerrusManager manager;

    private final Navigator navigator;
    private final ActionController actionController;
    private final MetadataStorage metadata;

    private String name;
    private PersonaEntity personaEntity;
    private Location lastLocation;
    private SkinData skinData;

    public Persona(NerrusManager manager, UUID uuid, String name, EntityType entityType) {
        this.manager = manager;
        this.uuid = uuid;
        this.name = name;
        this.entityType = entityType;
        this.navigator = new Navigator(this);
        this.actionController = new ActionController(this);
        this.metadata = new MetadataStorage();
    }

    public void spawn(Location location) {
        if (isSpawned()) {
            despawn();
        }
        this.lastLocation = location.clone();
        Entity entity = manager.getPlatformBridge().spawn(this, location);
        if (entity instanceof CraftPlayer craftPlayer && craftPlayer.getHandle() instanceof PersonaEntity pe) {
            personaEntity = pe;
        } else {
            manager.getLogger().severe("Entity " + getName() + " is not a valid PersonaEntity.");
            if (entity != null) {
                entity.remove();
            }
            return;
        }
        manager.getRegistry().register(this);
    }

    public void despawn() {
        if (!isSpawned()) {
            manager.getLogger().warning("Trying to despawn a `!isSpawned()` PersonaEntity.");
            return;
        }
        this.lastLocation = getLocation();
        int oldId = this.personaEntity.getId();
        manager.getPlatformBridge().despawn(this);
        this.personaEntity = null;
        manager.getRegistry().updateEntityId(this, oldId);
    }

    public boolean isSpawned() {
        return this.personaEntity != null && this.personaEntity.getBukkitEntity().isValid();
    }

    public void tick() {
        if (!isSpawned()) {
            return;
        }
        navigator.tick();
        actionController.tick();
    }

    public void teleport(Location location, TeleportCause cause) {
        if (!isSpawned()) {
            this.lastLocation = location;
            return;
        }
        this.personaEntity.getBukkitEntity().teleport(location, cause);
    }

    public void playAnimation(PlayerAnimation animation) {
        if (!isSpawned())
            return;
        manager.getPlatformBridge().playAnimation(this, animation);
    }

    public void speak(String message) {
        if (!isSpawned())
            return;
        manager.getPlatformBridge().broadcastChat(this, message);
    }

    public UUID getUniqueId() {
        return this.uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        if (isSpawned()) {
            manager.getPlatformBridge().respawn(this);
        }
    }

    @Nullable
    public SkinData getSkin() {
        return skinData;
    }

    public void setSkin(SkinData skinData) {
        this.skinData = skinData;
        if (isSpawned()) {
            manager.getPlatformBridge().refreshPlayerProfile(this);
        }
    }

    public Location getLocation() {
        return isSpawned() ? this.personaEntity.getBukkitEntity().getLocation() : lastLocation;
    }

    public Navigator getNavigator() {
        return navigator;
    }

    public NerrusManager getManager() {
        return manager;
    }

    @Nullable
    public Entity getEntity() {
        return (this.personaEntity != null && this.personaEntity.isAlive()) ? this.personaEntity.getBukkitEntity() : null;
    }

    @Nullable
    public PersonaEntity getPersonaEntity() {
        return personaEntity;
    }

    public ActionController getActionController() {
        return actionController;
    }

    public MetadataStorage getMetadata() {
        return metadata;
    }

    public EntityType getEntityType() {
        return entityType;
    }
}