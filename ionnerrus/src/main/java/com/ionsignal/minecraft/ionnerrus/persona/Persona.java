package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.animation.PlayerAnimation;
import com.ionsignal.minecraft.ionnerrus.persona.components.PhysicalBody;
import com.ionsignal.minecraft.ionnerrus.persona.components.impl.BukkitPhysicalBody;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinData;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class Persona {
    private final UUID uuid;
    private final EntityType entityType;
    private final NerrusManager manager;
    private final MetadataStorage metadata;

    private String name;
    private PersonaEntity personaEntity;
    private PhysicalBody physicalBody;
    private Location lastLocation;
    private SkinData skinData;

    public Persona(NerrusManager manager, UUID uuid, String name, EntityType entityType) {
        this.manager = manager;
        this.uuid = uuid;
        this.name = name;
        this.entityType = entityType;
        this.metadata = new MetadataStorage();
    }

    @SuppressWarnings("null")
    public void spawn(Location location) {
        if (isSpawned()) {
            despawn();
        }
        // Set last location
        this.lastLocation = location.clone();
        ServerLevel world = ((CraftWorld) location.getWorld()).getHandle();
        GameProfile profile = createGameProfile(uuid, name, skinData);
        MinecraftServer server = world.getServer();
        // Create NMS Entity
        this.personaEntity = new PersonaEntity(server, world, profile, this);
        personaEntity.setGameMode(GameType.SURVIVAL);
        personaEntity.setPos(location.getX(), location.getY(), location.getZ());
        personaEntity.setRot(location.getYaw(), location.getPitch());
        personaEntity.setYHeadRot(location.getYaw());
        // Create Bridge
        this.physicalBody = new BukkitPhysicalBody(this, this.personaEntity);
        // Register in World
        ClientboundPlayerInfoUpdatePacket addPlayerPacket = ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(personaEntity,
                false);
        for (org.bukkit.entity.Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) onlinePlayer).getHandle().connection.send(addPlayerPacket);
            personaEntity.grantVisibility(onlinePlayer);
        }
        world.addFreshEntity(personaEntity);
        // Register with CraftEngine to prevent NPEs during block interactions
        manager.getCraftEngineService().registerPersona(personaEntity.getBukkitEntity());
        // Register with NerrusManager
        manager.getRegistry().register(this);
    }

    @SuppressWarnings("null")
    public void despawn() {
        if (!isSpawned()) {
            manager.getLogger().warning("Trying to despawn a `!isSpawned()` PersonaEntity.");
            return;
        }
        // Unregister from CraftEngine
        manager.getCraftEngineService().unregisterPersona(personaEntity.getBukkitEntity());
        // Set last location
        this.lastLocation = getLocation();
        // Stop all physical operations
        if (this.physicalBody != null) {
            this.physicalBody.movement().stop();
            this.physicalBody.orientation().clearLookTarget();
            this.physicalBody.actions().cancelAction();
        }
        // Remove entity from world
        ClientboundPlayerInfoRemovePacket removePacket = new ClientboundPlayerInfoRemovePacket(java.util.List.of(personaEntity.getUUID()));
        for (org.bukkit.entity.Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) onlinePlayer).getHandle().connection.send(removePacket);
        }
        personaEntity.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        // Cleanup references
        int personaEntityId = this.personaEntity.getId();
        this.personaEntity = null;
        this.physicalBody = null;
        manager.getRegistry().updateEntityId(this, personaEntityId);
    }

    public boolean isSpawned() {
        return this.personaEntity != null && this.personaEntity.getBukkitEntity().isValid();
    }

    public void tick() {
        if (!isSpawned()) {
            return;
        }
        if (physicalBody != null) {
            physicalBody.tick();
        }
    }

    /**
     * Gets the PhysicalBody interface for this Persona.
     * This is the sole entry point for interacting with the physical world.
     */
    public PhysicalBody getPhysicalBody() {
        if (!isSpawned() || this.physicalBody == null) {
            throw new IllegalStateException("Cannot access PhysicalBody: Persona '" + getName() + "' is not spawned.");
        }
        return this.physicalBody;
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
        this.physicalBody.actions().playAnimation(animation);
    }

    public void speak(String message) {
        if (!isSpawned()) {
            return;
        }
        // Currently we only use chat bubbles
        IonNerrus plugin = IonNerrus.getInstance();
        if (plugin.getPluginConfig().isChatBubblesEnabled() && plugin.getChatBubbleService() != null) {
            plugin.getChatBubbleService().showBubble(this.getPersonaEntity().getBukkitEntity(), message);
        }
    }

    public Location getLocation() {
        return isSpawned() ? this.physicalBody.state().getLocation() : lastLocation;
    }

    @Nullable
    public PlayerInventory getInventory() {
        if (!isSpawned())
            return null;
        return this.physicalBody.state().getInventory();
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
            Location loc = getLocation();
            despawn();
            spawn(loc);
        }
    }

    @Nullable
    public SkinData getSkin() {
        return skinData;
    }

    public void setSkin(SkinData skinData) {
        this.skinData = skinData;
        if (isSpawned()) {
            refreshPlayerProfile();
        }
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

    public MetadataStorage getMetadata() {
        return metadata;
    }

    public EntityType getEntityType() {
        return entityType;
    }

    private GameProfile createGameProfile(UUID uuid, String name, SkinData skin) {
        GameProfile profile = new GameProfile(uuid, name);
        if (skin != null) {
            profile.getProperties().put("textures", new Property("textures", skin.texture(), skin.signature()));
        }
        return profile;
    }

    @SuppressWarnings("null")
    private void refreshPlayerProfile() {
        GameProfile profile = personaEntity.getGameProfile();
        if (skinData != null) {
            profile.getProperties().removeAll("textures");
            profile.getProperties().put("textures", new Property("textures", skinData.texture(), skinData.signature()));
        }
        var trackedEntity = personaEntity.level().getChunkSource().chunkMap.entityMap.get(personaEntity.getId());
        if (trackedEntity != null) {
            ClientboundPlayerInfoRemovePacket removePacket = new ClientboundPlayerInfoRemovePacket(
                    java.util.List.of(personaEntity.getUUID()));
            ClientboundPlayerInfoUpdatePacket addPacket = ClientboundPlayerInfoUpdatePacket
                    .createPlayerInitializing(java.util.List.of(personaEntity));
            for (var connection : trackedEntity.seenBy) {
                connection.send(removePacket);
                connection.send(addPacket);
            }
        }
    }

    @SuppressWarnings("null")
    public void showToPlayer(org.bukkit.entity.Player viewer) {
        if (!isSpawned())
            return;
        ServerPlayer viewerPlayer = ((CraftPlayer) viewer).getHandle();
        ClientboundPlayerInfoUpdatePacket addPacket = ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(personaEntity,
                false);
        viewerPlayer.connection.send(addPacket);
        personaEntity.grantVisibility(viewer);
    }
}