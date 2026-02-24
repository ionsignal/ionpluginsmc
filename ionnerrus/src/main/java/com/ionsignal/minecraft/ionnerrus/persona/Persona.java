package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.animation.PlayerAnimation;
import com.ionsignal.minecraft.ionnerrus.persona.components.PhysicalBody;
import com.ionsignal.minecraft.ionnerrus.persona.components.impl.BukkitPhysicalBody;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import com.google.common.collect.Multimap;
import com.google.common.collect.LinkedHashMultimap;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class Persona {
    private final UUID uuid;
    private final EntityType entityType;
    private final NerrusManager manager;
    private final MetadataStorage metadata;

    @Nullable
    private UUID ownerId;

    @Nullable
    private UUID definitionId;

    @Nullable
    private UUID sessionId;

    private String name;
    private PersonaEntity personaEntity;
    private PhysicalBody physicalBody;
    private Location lastLocation;
    private PersonaSkinData skinData;

    public Persona(NerrusManager manager, UUID uuid, String name, EntityType entityType) {
        this.manager = manager;
        this.uuid = uuid;
        this.name = name;
        this.entityType = entityType;
        this.metadata = new MetadataStorage();
    }

    public void setDefinitionId(@Nullable UUID definitionId) {
        this.definitionId = definitionId;
    }

    @Nullable
    public UUID getDefinitionId() {
        return definitionId;
    }

    public void setOwnerId(@Nullable UUID ownerId) {
        this.ownerId = ownerId;
    }

    @Nullable
    public UUID getOwnerId() {
        return ownerId;
    }

    public void setSessionId(@Nullable UUID sessionId) {
        this.sessionId = sessionId;
    }

    @Nullable
    public UUID getSessionId() {
        return sessionId;
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
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
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
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) onlinePlayer).getHandle().connection.send(removePacket);
        }
        personaEntity.remove(RemovalReason.DISCARDED);
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
    public PersonaSkinData getSkin() {
        return skinData;
    }

    public void setSkin(PersonaSkinData skinData) {
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

    /**
     * Creates a GameProfile with properties populated upfront.
     * Required because GameProfile and PropertyMap are immutable in 1.21+.
     */
    private GameProfile createGameProfile(UUID uuid, String name, PersonaSkinData skin) {
        if (skin != null && skin.textureValue() != null && !skin.textureValue().isEmpty() && !skin.textureSignature().isEmpty()) {
            Multimap<String, Property> properties = LinkedHashMultimap.create();
            properties.put("textures", new Property("textures", skin.textureValue(), skin.textureSignature()));
            PropertyMap propertyMap = new PropertyMap(properties);
            return new GameProfile(uuid, name, propertyMap);
        } else {
            return new GameProfile(uuid, name);
        }
    }

    /**
     * Replaces the GameProfile and performs a client-side "Blink" to hot-swap the skin.
     * This updates the Tab List globally and safely resends the 3D entity to nearby viewers
     * without destroying the server-side PersonaEntity or interrupting its AI tasks.
     */
    @SuppressWarnings("null")
    private void refreshPlayerProfile() {
        // Create the new profile with the updated skin and apply it to the entity
        GameProfile newProfile = createGameProfile(uuid, name, skinData);
        personaEntity.setGameProfile(newProfile);
        // Update the Tab List for all online players
        ClientboundPlayerInfoRemovePacket removeInfo = new ClientboundPlayerInfoRemovePacket(
                java.util.List.of(personaEntity.getUUID()));
        ClientboundPlayerInfoUpdatePacket addInfo = ClientboundPlayerInfoUpdatePacket
                .createSinglePlayerInitializing(personaEntity, false);
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            var conn = ((CraftPlayer) onlinePlayer).getHandle().connection;
            conn.send(removeInfo);
            conn.send(addInfo);
        }
        // Blink the 3D entity for players currently tracking it
        var chunkMap = personaEntity.level().getChunkSource().chunkMap;
        var trackedEntity = chunkMap.entityMap.get(personaEntity.getId());
        if (trackedEntity != null) {
            ClientboundRemoveEntitiesPacket removeEntity = new ClientboundRemoveEntitiesPacket(personaEntity.getId());
            for (var connection : trackedEntity.seenBy) {
                // Destroy the old 3D model for this viewer
                connection.send(removeEntity);
                // Force the tracker to resend all spawn, metadata, and equipment packets
                // using the fresh GameProfile we just applied.
                ServerPlayer viewer = connection.getPlayer();
                trackedEntity.serverEntity.sendPairingData(viewer, viewer.connection::send);
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